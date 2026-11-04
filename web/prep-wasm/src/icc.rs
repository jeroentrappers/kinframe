//! Just enough ICC to convert matrix/TRC RGB profiles (Display P3, Adobe RGB, ProPhoto, camera "sRGB-ish"
//! profiles…) to sRGB. That covers what phones and cameras embed. LUT-based profiles (A2B0 only), CMYK and grey
//! profiles are not converted: the pixels are taken as sRGB, which is what the server assumes for a master without
//! a profile anyway.

/// sRGB primaries adapted to the D50 profile connection space (Bradford), as in the ICC sRGB v2 profile.
const SRGB_D50: [[f32; 3]; 3] = [
    [0.436_074_7, 0.385_064_9, 0.143_080_4],
    [0.222_504_5, 0.716_878_6, 0.060_616_9],
    [0.013_932_2, 0.097_104_5, 0.714_173_3],
];

/// Display P3 (D65 white, sRGB transfer) in the same D50 space: what iPhones and most Android phones use.
pub const DISPLAY_P3_D50: [[f32; 3]; 3] = [
    [0.515_102, 0.291_965, 0.157_153],
    [0.241_196, 0.692_245, 0.066_561],
    [-0.001_053, 0.041_893, 0.784_356],
];

/// Entries in the linear-light to sRGB encoding table.
const ENC_STEPS: usize = 4096;

#[derive(Clone, Debug)]
enum Curve {
    Gamma(f32),
    Table(Vec<f32>),
    /// ICC parametric curve type 0..4: g, a, b, c, d, e, f.
    Param(u16, [f32; 7]),
}

impl Curve {
    fn eval(&self, x: f32) -> f32 {
        let y = match self {
            Curve::Gamma(g) => x.powf(*g),
            Curve::Table(t) => {
                let pos = x * (t.len() - 1) as f32;
                let i = (pos.floor() as usize).min(t.len() - 2);
                let f = pos - i as f32;
                t[i] * (1.0 - f) + t[i + 1] * f
            }
            Curve::Param(kind, p) => {
                let [g, a, b, c, d, e, f] = *p;
                match kind {
                    0 => x.powf(g),
                    1 => {
                        if x >= -b / a {
                            (a * x + b).max(0.0).powf(g)
                        } else {
                            0.0
                        }
                    }
                    2 => {
                        if x >= -b / a {
                            (a * x + b).max(0.0).powf(g) + c
                        } else {
                            c
                        }
                    }
                    3 => {
                        if x >= d {
                            (a * x + b).max(0.0).powf(g)
                        } else {
                            c * x
                        }
                    }
                    _ => {
                        if x >= d {
                            (a * x + b).max(0.0).powf(g) + e
                        } else {
                            c * x + f
                        }
                    }
                }
            }
        };
        y.clamp(0.0, 1.0)
    }
}

fn srgb_decode(v: f32) -> f32 {
    if v <= 0.040_45 {
        v / 12.92
    } else {
        ((v + 0.055) / 1.055).powf(2.4)
    }
}

fn srgb_encode(v: f32) -> f32 {
    if v <= 0.003_130_8 {
        v * 12.92
    } else {
        1.055 * v.powf(1.0 / 2.4) - 0.055
    }
}

fn be16(b: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_be_bytes(b.get(at..at + 2)?.try_into().ok()?))
}

fn be32(b: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_be_bytes(b.get(at..at + 4)?.try_into().ok()?))
}

fn s15f16(b: &[u8], at: usize) -> Option<f32> {
    Some(be32(b, at)? as i32 as f32 / 65536.0)
}

fn tag<'a>(icc: &'a [u8], sig: &[u8; 4]) -> Option<&'a [u8]> {
    let count = be32(icc, 128)? as usize;
    for i in 0..count.min(256) {
        let e = 132 + i * 12;
        if icc.get(e..e + 4)? == sig {
            let off = be32(icc, e + 4)? as usize;
            let len = be32(icc, e + 8)? as usize;
            return icc.get(off..off.checked_add(len)?);
        }
    }
    None
}

fn xyz(icc: &[u8], sig: &[u8; 4]) -> Option<[f32; 3]> {
    let t = tag(icc, sig)?;
    if t.get(0..4)? != b"XYZ " {
        return None;
    }
    Some([s15f16(t, 8)?, s15f16(t, 12)?, s15f16(t, 16)?])
}

fn curve(icc: &[u8], sig: &[u8; 4]) -> Option<Curve> {
    let t = tag(icc, sig)?;
    match t.get(0..4)? {
        b"curv" => {
            let n = be32(t, 8)? as usize;
            match n {
                0 => Some(Curve::Gamma(1.0)),
                1 => Some(Curve::Gamma(be16(t, 12)? as f32 / 256.0)),
                _ if n <= 65536 => {
                    let mut v = Vec::with_capacity(n);
                    for i in 0..n {
                        v.push(be16(t, 12 + i * 2)? as f32 / 65535.0);
                    }
                    Some(Curve::Table(v))
                }
                _ => None,
            }
        }
        b"para" => {
            let kind = be16(t, 8)?;
            let n = match kind {
                0 => 1,
                1 => 3,
                2 => 4,
                3 => 5,
                4 => 7,
                _ => return None,
            };
            let mut p = [1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0];
            for (i, slot) in p.iter_mut().enumerate().take(n) {
                *slot = s15f16(t, 12 + i * 4)?;
            }
            if (kind == 1 || kind == 2) && p[1] == 0.0 {
                return None;
            }
            Some(Curve::Param(kind, p))
        }
        _ => None,
    }
}

fn invert(m: &[[f32; 3]; 3]) -> Option<[[f32; 3]; 3]> {
    let [[a, b, c], [d, e, f], [g, h, i]] = *m;
    let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    if det.abs() < 1e-9 {
        return None;
    }
    let k = 1.0 / det;
    Some([
        [(e * i - f * h) * k, (c * h - b * i) * k, (b * f - c * e) * k],
        [(f * g - d * i) * k, (a * i - c * g) * k, (c * d - a * f) * k],
        [(d * h - e * g) * k, (b * g - a * h) * k, (a * e - b * d) * k],
    ])
}

fn mul(a: &[[f32; 3]; 3], b: &[[f32; 3]; 3]) -> [[f32; 3]; 3] {
    let mut out = [[0.0; 3]; 3];
    for (r, row) in out.iter_mut().enumerate() {
        for (c, v) in row.iter_mut().enumerate() {
            *v = (0..3).map(|k| a[r][k] * b[k][c]).sum();
        }
    }
    out
}

/// Converts 8-bit pixels from a matrix/TRC RGB space to sRGB, through linear light and lookup tables.
pub struct ToSrgb {
    lin: [[f32; 256]; 3],
    m: [[f32; 3]; 3],
    enc: Vec<u8>,
}

impl ToSrgb {
    fn new(curves: [Curve; 3], primaries_d50: [[f32; 3]; 3]) -> Option<Self> {
        let m = mul(&invert(&SRGB_D50)?, &primaries_d50);
        let mut lin = [[0.0; 256]; 3];
        for (ch, c) in curves.iter().enumerate() {
            for (i, v) in lin[ch].iter_mut().enumerate() {
                *v = c.eval(i as f32 / 255.0);
            }
        }
        let identity = (0..3).all(|r| (0..3).all(|c| (m[r][c] - if r == c { 1.0 } else { 0.0 }).abs() < 0.003))
            && lin.iter().all(|t| {
                t.iter()
                    .enumerate()
                    .all(|(i, v)| (v - srgb_decode(i as f32 / 255.0)).abs() < 0.002)
            });
        if identity {
            return None; // Already sRGB: nothing to do.
        }
        let enc = (0..ENC_STEPS)
            .map(|i| (srgb_encode(i as f32 / (ENC_STEPS - 1) as f32) * 255.0).round() as u8)
            .collect();
        Some(ToSrgb { lin, m, enc })
    }

    /// From an ICC profile; `None` when it is (close to) sRGB already, or not a matrix/TRC RGB profile.
    pub fn from_icc(icc: &[u8]) -> Option<Self> {
        if icc.len() < 132 || icc.get(16..20)? != b"RGB " {
            return None;
        }
        let (r, g, b) = (xyz(icc, b"rXYZ")?, xyz(icc, b"gXYZ")?, xyz(icc, b"bXYZ")?);
        let primaries = [[r[0], g[0], b[0]], [r[1], g[1], b[1]], [r[2], g[2], b[2]]];
        let curves = [curve(icc, b"rTRC")?, curve(icc, b"gTRC")?, curve(icc, b"bTRC")?];
        Self::new(curves, primaries)
    }

    /// Display P3 with the sRGB transfer curve (HEIF `nclx` colour primaries 12).
    pub fn display_p3() -> Self {
        let srgb = Curve::Param(3, [2.4, 1.0 / 1.055, 0.055 / 1.055, 1.0 / 12.92, 0.040_45, 0.0, 0.0]);
        Self::new([srgb.clone(), srgb.clone(), srgb], DISPLAY_P3_D50).expect("P3 is not sRGB")
    }

    /// Converts RGBA8 pixels in place (alpha untouched).
    pub fn apply(&self, rgba: &mut [u8]) {
        let scale = (ENC_STEPS - 1) as f32;
        for px in rgba.as_chunks_mut::<4>().0 {
            let l = [
                self.lin[0][px[0] as usize],
                self.lin[1][px[1] as usize],
                self.lin[2][px[2] as usize],
            ];
            for (c, out) in px.iter_mut().take(3).enumerate() {
                let v = self.m[c][0] * l[0] + self.m[c][1] * l[1] + self.m[c][2] * l[2];
                *out = self.enc[(v.clamp(0.0, 1.0) * scale + 0.5) as usize];
            }
        }
    }
}

#[cfg(test)]
pub mod tests {
    use super::*;

    fn xyz_tag(v: [f32; 3]) -> Vec<u8> {
        let mut t = b"XYZ \0\0\0\0".to_vec();
        for c in v {
            t.extend(((c * 65536.0).round() as i32).to_be_bytes());
        }
        t
    }

    /// A minimal matrix/TRC ICC profile with the given D50 primaries and the sRGB transfer curve.
    pub fn profile(primaries: [[f32; 3]; 3]) -> Vec<u8> {
        let mut para = b"para\0\0\0\0\0\x03\0\0".to_vec();
        for p in [2.4f32, 1.0 / 1.055, 0.055 / 1.055, 1.0 / 12.92, 0.040_45] {
            para.extend(((p * 65536.0).round() as i32).to_be_bytes());
        }
        let col = |c: usize| [primaries[0][c], primaries[1][c], primaries[2][c]];
        let tags: Vec<(&[u8; 4], Vec<u8>)> = vec![
            (b"rXYZ", xyz_tag(col(0))),
            (b"gXYZ", xyz_tag(col(1))),
            (b"bXYZ", xyz_tag(col(2))),
            (b"rTRC", para.clone()),
            (b"gTRC", para.clone()),
            (b"bTRC", para),
        ];
        let mut head = vec![0u8; 128];
        head[12..16].copy_from_slice(b"mntr");
        head[16..20].copy_from_slice(b"RGB ");
        head[20..24].copy_from_slice(b"XYZ ");
        head[36..40].copy_from_slice(b"acsp");
        let mut table = (tags.len() as u32).to_be_bytes().to_vec();
        let mut data = Vec::new();
        let base = 128 + 4 + tags.len() * 12;
        for (sig, body) in &tags {
            table.extend_from_slice(*sig);
            table.extend(((base + data.len()) as u32).to_be_bytes());
            table.extend((body.len() as u32).to_be_bytes());
            data.extend_from_slice(body);
            while data.len() % 4 != 0 {
                data.push(0);
            }
        }
        let mut icc = [head, table, data].concat();
        let len = icc.len() as u32;
        icc[0..4].copy_from_slice(&len.to_be_bytes());
        icc
    }

    #[test]
    fn srgb_profiles_are_left_alone() {
        assert!(ToSrgb::from_icc(&profile(SRGB_D50)).is_none());
        assert!(ToSrgb::from_icc(b"junk").is_none());
    }

    #[test]
    fn display_p3_converts_like_the_builtin() {
        let parsed = ToSrgb::from_icc(&profile(DISPLAY_P3_D50)).expect("P3 profile");
        let builtin = ToSrgb::display_p3();
        let mut a = vec![255, 0, 0, 255, 128, 64, 32, 255, 90, 90, 90, 255];
        let mut b = a.clone();
        parsed.apply(&mut a);
        builtin.apply(&mut b);
        for (x, y) in a.iter().zip(&b) {
            assert!((*x as i32 - *y as i32).abs() <= 1, "{a:?} vs {b:?}");
        }
        // Pure P3 red is outside sRGB: clipped to red. Greys stay grey. Warm tones get more saturated.
        assert_eq!(a[0], 255);
        assert!(a[1] < 8 && a[2] < 8, "{a:?}");
        assert!((a[8] as i32 - 90).abs() <= 1 && a[8] == a[9] && a[9] == a[10], "{a:?}");
        assert!(a[4] > 128 && a[6] < 32, "{a:?}");
    }
}
