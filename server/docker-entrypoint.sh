#!/bin/sh
# Everything the server writes (database, WAL files, TLS key) is for its user only.
umask 077
exec /opt/subtrack/bin/subtrack-server "$@"
