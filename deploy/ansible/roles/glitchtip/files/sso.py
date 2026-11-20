# Logto sign-in for kinframe's operators (docs/spec/crash-reporting.md §1). Idempotent. Reads the JSON that
# `kinframe-server bootstrap-logto` writes with KINFRAME_LOGTO_GLITCHTIP_URL set (glitchtip-oidc.json) on stdin:
#   docker compose exec -T web ./manage.py shell -c "$(cat sso.py)" < glitchtip-oidc.json
# The script is the command and the secret comes on stdin, so it never appears in a process list or in the output.
#
# It upserts an instance-wide allauth SocialApp (provider openid_connect, provider_id "logto") and links each
# operator's Logto user ID (sub) to the GlitchTip user with that email; links to other Logto users are removed.
# Social sign-up stays closed (ENABLE_USER_REGISTRATION=False, so ENABLE_SOCIAL_APPS_USER_REGISTRATION too), and the
# app is never linked to an organization (that would let anyone who signs in at Logto create an account), so a Logto
# sign-in without such a link is refused. Email matching (allauth EMAIL_AUTHENTICATION) stays off.
import json
import sys

from allauth.socialaccount.models import SocialAccount, SocialApp
from django.db import transaction

from apps.users.models import User

cfg = json.load(sys.stdin)
pid = cfg.get("provider_id") or "logto"
for key in ("issuer", "client_id", "client_secret"):
    if not cfg.get(key):
        raise SystemExit(f"glitchtip-oidc.json has no {key}")

with transaction.atomic():
    app = SocialApp.objects.filter(provider_id=pid).first()
    created = app is None
    if created:
        app = SocialApp(provider_id=pid)
    want = {"provider": "openid_connect", "name": "kinframe", "client_id": cfg["client_id"],
            "secret": cfg["client_secret"], "key": ""}
    settings = dict(app.settings or {}, server_url=cfg["issuer"])
    dirty = [k for k, v in want.items() if getattr(app, k) != v] + (["settings"] if settings != app.settings else [])
    if created or dirty:
        for k, v in want.items():
            setattr(app, k, v)
        app.settings = settings
        app.save()
        print("CHANGED social app kinframe (provider_id %s): %s" % (pid, "created" if created else ", ".join(dirty)))
    # An organization-linked social app is an open invitation in GlitchTip (glitchtip/adapters.py); never allow one.
    if hasattr(app, "organizationsocialapp"):
        app.organizationsocialapp.delete()
        print("CHANGED social app kinframe: removed its organization link")

    subs = set()
    for op in cfg.get("operators") or []:
        email, sub = op["email"], op["sub"]
        user = User.objects.filter(email__iexact=email).first()
        if user is None:
            print(f"SKIP {email}: no GlitchTip account")
            continue
        subs.add(sub)
        acct = SocialAccount.objects.filter(provider=pid, uid=sub).first()
        if acct is None:
            SocialAccount.objects.create(user=user, provider=pid, uid=sub)
            print(f"CHANGED linked {email} to Logto user {sub}")
        elif acct.user_id != user.pk:
            acct.user = user
            acct.save(update_fields=["user"])
            print(f"CHANGED moved Logto user {sub} to {email}")
        else:
            print(f"OK {email}")
    for acct in SocialAccount.objects.filter(provider=pid).exclude(uid__in=subs).select_related("user"):
        acct.delete()
        print(f"CHANGED unlinked Logto user {acct.uid} from {acct.user.email} (not an operator)")
