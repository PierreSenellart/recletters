#!/usr/bin/env bash
# Create (or recreate) the recletters_test database for running integration
# tests. Uses peer/socket auth so no passwords appear in the test config
# (see conf/test.conf).
#
# Usage:
#   test/create_test_db.sh           # defaults to PostgreSQL
#   test/create_test_db.sh pg
#   test/create_test_db.sh mysql
#
# PostgreSQL requires the current Unix user to have CREATEDB (or be a
# superuser). MySQL/MariaDB requires the current Unix user to have a matching
# 'username'@'localhost' MySQL user authenticated via `unix_socket` / `auth_socket`,
# with privileges on `recletters\_%`. See docs/develop.md.
set -euo pipefail

ENGINE="${1:-pg}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DB="recletters_test"

case "$ENGINE" in
  pg|postgres|postgresql)
    psql -d postgres -c "DROP DATABASE IF EXISTS $DB;" >/dev/null
    psql -d postgres -c "CREATE DATABASE $DB;"         >/dev/null
    ln -sf 1-postgres.sql "$ROOT/conf/evolutions/default/1.sql"
    echo "PostgreSQL test DB '$DB' ready."
    ;;
  mysql|mariadb)
    mysql -e "DROP DATABASE IF EXISTS \`$DB\`;"
    mysql -e "CREATE DATABASE \`$DB\` CHARACTER SET utf8mb4;"
    # A second, empty database that imitates a sibling HotCRP install. The
    # HotCRPImporter spec creates Paper / PaperOption tables and fixtures in
    # there. Play Evolutions does not touch this DB (see conf/test-mysql.conf).
    mysql -e "DROP DATABASE IF EXISTS hotcrp_test;"
    mysql -e "CREATE DATABASE hotcrp_test CHARACTER SET utf8mb4;"
    ln -sf 1-mysql.sql "$ROOT/conf/evolutions/default/1.sql"
    echo "MySQL test DB '$DB' and 'hotcrp_test' ready."
    ;;
  *)
    echo "Unknown engine: $ENGINE (use pg | mysql)" >&2
    exit 2
    ;;
esac
