# GlitchTip first run for kinframe: the operator account, organization "kinframe", the client projects (docs/spec/
# crash-reporting.md §1) with an e-mail alert each, and an API token for symbol and source map uploads. Idempotent.
#   GT_EMAIL=you@example.com GT_PASSWORD=... GT_DOMAIN=errors.example.com \
#     docker exec -i -e GT_EMAIL -e GT_PASSWORD -e GT_DOMAIN glitchtip-web-1 ./manage.py shell < bootstrap.py
# Prints each project's DSN, and the token last (keep it: SENTRY_AUTH_TOKEN for release builds).
import os
from apps.users.models import User
from apps.organizations_ext.models import Organization, OrganizationUser, OrganizationOwner
from apps.projects.models import Project, ProjectKey
from apps.alerts.models import ProjectAlert, AlertRecipient
from apps.api_tokens.models import APIToken
from apps.teams.models import Team
EMAIL = os.environ["GT_EMAIL"]
DOMAIN = os.environ["GT_DOMAIN"]
u, made = User.objects.get_or_create(email=EMAIL, defaults={"is_superuser": True, "is_staff": True})
if made:
    u.set_password(os.environ["GT_PASSWORD"]); u.save()
org, _ = Organization.objects.get_or_create(slug="kinframe", defaults={"name": "kinframe", "scrub_ip_addresses": True})
ou, _ = OrganizationUser.objects.get_or_create(organization=org, user=u, defaults={"role": 3})
OrganizationOwner.objects.get_or_create(organization=org, defaults={"organization_user": ou})
team, _ = Team.objects.get_or_create(organization=org, slug="kinframe")
team.members.add(ou)
PROJECTS = [("kinframe-server", "go"), ("kinframe-web", "javascript-sveltekit"), ("family-android", "android"),
            ("frame-android", "android"), ("family-apple", "apple-ios")]
for slug, platform in PROJECTS:
    p, _ = Project.objects.get_or_create(organization=org, slug=slug, defaults={"name": slug, "platform": platform, "scrub_ip_addresses": True})
    p.teams.add(team)
    if not ProjectAlert.objects.filter(project=p).exists():
        a = ProjectAlert.objects.create(project=p, name="New issue", timespan_minutes=1, quantity=1)
        AlertRecipient.objects.create(alert=a, recipient_type="email")
    k = ProjectKey.objects.filter(project=p, is_active=True).first()
    print(f"DSN {slug} https://{k.public_key.hex}@{DOMAIN}/{p.id}")
names = list(APIToken._meta.get_field("scopes").flags)
want = {"project:read", "project:write", "project:releases", "org:read", "event:read"}
tok = APIToken.objects.filter(user=u, label="release uploads").first()
if not tok:
    tok = APIToken.objects.create(user=u, label="release uploads", scopes=sum(1 << i for i, n in enumerate(names) if n in want))
print(f"TOKEN {tok.token}")
