//! End-to-end bulk import against a real kinframe server in dev-auth mode:
//!
//!     KINFRAME_E2E_URL=http://127.0.0.1:8131 cargo test --test e2e -- --ignored --nocapture
//!
//! Enrolls a fake frame, claims it with its code, imports a folder of generated photos through the engine
//! (tus uploads + batched sends), then imports the same folder again and expects only duplicates.

use std::sync::Arc;
use std::time::Duration;

use kinframe_desktop::api::Api;
use kinframe_desktop::auth::{Credentials, MemoryStore, Session};
use kinframe_desktop::engine::{Engine, EngineConfig};
use kinframe_desktop::queue::{FrameRef, Job};

async fn wait<F: Fn() -> bool>(what: &str, f: F) {
    for _ in 0..600 {
        if f() {
            return;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    panic!("timed out waiting for {what}");
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
#[ignore = "needs a dev server: set KINFRAME_E2E_URL"]
async fn bulk_import_against_dev_server() {
    let base = std::env::var("KINFRAME_E2E_URL").expect("KINFRAME_E2E_URL");
    let http = reqwest::Client::new();
    let server = kinframe_desktop::discovery::normalize_server_url(&base).unwrap();
    let d = kinframe_desktop::discovery::fetch(&http, &server).await.unwrap();
    assert!(d.dev_auth, "the e2e test needs dev auth");

    // A fake frame: enroll, get a token and a code.
    let enroll: serde_json::Value = http
        .post(d.api_url("devices/enroll"))
        .json(&serde_json::json!({
            "hardware": {"model": "desktop-e2e"}, "screen": {"width": 1280, "height": 800},
            "app": {"version": "0.0.0", "version_code": 1}
        }))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let tok: serde_json::Value = http
        .post(d.api_url("devices/token"))
        .json(&serde_json::json!({"device_id": enroll["device_id"], "device_secret": enroll["device_secret"]}))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    let code: serde_json::Value = http
        .post(d.api_url("device/code"))
        .bearer_auth(tok["access_token"].as_str().unwrap())
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();

    // Sign in (dev auth) and claim the frame.
    let email = format!("e2e-{}@example.org", uuid::Uuid::new_v4());
    let session = Session::new(
        http.clone(),
        d.clone(),
        Arc::new(MemoryStore::default()),
        Credentials::Dev {
            email,
            name: "Desktop E2E".into(),
        },
        None,
    );
    let api = Api::new(http.clone(), Arc::new(session), "en");
    let redeemed = api
        .redeem(code["code"].as_str().unwrap(), &uuid::Uuid::new_v4().to_string())
        .await
        .unwrap();
    assert_eq!(redeemed.status, "claimed");
    let frame = redeemed.frame;

    // A folder of real JPEGs.
    let dir = tempfile::tempdir().unwrap();
    let album = dir.path().join("E2E album");
    std::fs::create_dir_all(album.join("day 2")).unwrap();
    for i in 0..7u32 {
        let img = image::RgbImage::from_fn(320, 200, |x, y| {
            image::Rgb([(x + i * 30) as u8, y as u8, (i * 35) as u8])
        });
        let sub = if i < 4 { album.clone() } else { album.join("day 2") };
        img.save(sub.join(format!("IMG_{i}.jpg"))).unwrap();
    }
    let cancel = std::sync::atomic::AtomicBool::new(false);
    let scanned = kinframe_desktop::scan::scan(std::slice::from_ref(&album), &cancel, |_| {});
    assert_eq!(scanned.files.len(), 7);

    let engine = Engine::start(
        &tokio::runtime::Handle::current(),
        EngineConfig::new(dir.path().join("queue.json"), dir.path().join("sent.json")),
        |_| {},
    );
    engine.set_api(Some(api.clone()));
    let frames = vec![FrameRef {
        id: frame.id.clone(),
        name: frame.name.clone(),
    }];
    let key = d.server_key();
    let job = engine.add_job(Job::new(&key, "E2E album", frames.clone(), &scanned.files, "{folder}"));
    let e = engine.clone();
    wait("the import", move || e.with_queue(|q| q.job(job).unwrap().is_done())).await;
    let (counts, sends) = engine.with_queue(|q| {
        let j = q.job(job).unwrap();
        (j.counts(), j.batches.len())
    });
    println!("first import: {counts:?}, {sends} sends");
    assert_eq!(counts.sent, 7, "{counts:?}");
    let max = d.limits.max_items_per_send as usize;
    assert_eq!(sends, 7usize.div_ceil(max));

    let history = api.sends(None, 20).await.unwrap();
    let items: usize = history.sends.iter().map(|s| s.items.len()).sum();
    assert_eq!(items, 7);
    assert!(history.sends.iter().all(|s| s.frames[0].frame_id == frame.id));

    // Same folder again: the local index skips everything.
    let again = engine.add_job(Job::new(&key, "E2E again", frames, &scanned.files, ""));
    let e = engine.clone();
    wait("the second import", move || {
        e.with_queue(|q| q.job(again).unwrap().is_done())
    })
    .await;
    let c = engine.with_queue(|q| q.job(again).unwrap().counts());
    println!("second import: {c:?}");
    assert_eq!(c.duplicate, 7);

    // Without the local index the server still recognises the file (tus creation `Kinframe-Existing-Item`) once
    // it has processed it.
    for _ in 0..120 {
        let h = api.sends(None, 20).await.unwrap();
        if h.sends.iter().flat_map(|s| &s.items).all(|i| i.status != "processing") {
            break;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    let f = &scanned.files[0];
    let sha = kinframe_desktop::dedupe::hash_file(&f.path, |_| {}).unwrap();
    let tus = kinframe_desktop::tus::Tus::new(http.clone(), d.api_url("uploads"));
    let created = tus
        .create(
            api.session.as_ref(),
            f.size,
            &kinframe_desktop::tus::Metadata {
                filename: "x.jpg",
                filetype: "image/jpeg",
                sha256: Some(&sha),
            },
        )
        .await
        .unwrap();
    println!("re-upload of a known file: {created:?}");
    assert!(
        matches!(created, kinframe_desktop::tus::Created::Existing(_)),
        "{created:?}"
    );
}
