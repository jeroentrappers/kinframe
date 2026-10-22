#!/bin/sh
# One-shot setup of the bundled object store for kinframe (idempotent):
#  - the media bucket, private (no anonymous access)
#  - a policy allowing only object read/write/delete in that bucket (and prefix) plus listing it
#  - kinframe's own user with that policy, so the server never holds the root credentials
# Runs in the Silo image (which ships `mc`) with minio.env; prints "nothing changed" when all was in place.
set -eu

: "${MINIO_ROOT_USER:?}" "${MINIO_ROOT_PASSWORD:?}" "${KINFRAME_S3_BUCKET:?}" "${KINFRAME_S3_ACCESS_KEY:?}" "${KINFRAME_S3_SECRET_KEY:?}"
export MC_CONFIG_DIR=/tmp/.mc
prefix="${KINFRAME_S3_PREFIX:-}"
bucket="$KINFRAME_S3_BUCKET"
user="$KINFRAME_S3_ACCESS_KEY"
changed=0

i=0
until mc alias set kf http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1 && mc ready kf >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    echo "object store not ready after 120 s" >&2
    exit 1
  fi
  sleep 2
done

if ! mc stat kf/"$bucket" >/dev/null 2>&1; then
  mc mb kf/"$bucket"
  changed=1
fi
case "$(mc anonymous get kf/"$bucket" 2>/dev/null)" in
  *'`private`'*) ;;
  *) mc anonymous set private kf/"$bucket" >/dev/null; changed=1 ;;
esac

policy=/tmp/kinframe-media.json
cat >"$policy" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Action": ["s3:GetBucketLocation", "s3:ListBucket", "s3:ListBucketMultipartUploads"],
     "Resource": ["arn:aws:s3:::$bucket"]},
    {"Effect": "Allow",
     "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"],
     "Resource": ["arn:aws:s3:::$bucket/$prefix*"]}
  ]
}
EOF
# Re-applied on every run (an unchanged policy is a no-op), so bucket or prefix changes take effect.
mc admin policy info kf kinframe-media >/dev/null 2>&1 || changed=1
mc admin policy create kf kinframe-media "$policy" >/dev/null

# Creating an existing user only resets its secret key, which keeps a rotated vault value in effect.
if ! mc admin user info kf "$user" >/dev/null 2>&1; then
  changed=1
fi
mc admin user add kf "$user" "$KINFRAME_S3_SECRET_KEY" >/dev/null
case "$(mc admin user info kf "$user" 2>/dev/null)" in
  *'PolicyName: kinframe-media'*) ;;
  *) mc admin policy attach kf kinframe-media --user "$user" >/dev/null; changed=1 ;;
esac

if [ "$changed" = 0 ]; then
  echo "minio-bootstrap: nothing changed"
else
  echo "minio-bootstrap: bucket $bucket and user $user ready"
fi
