.PHONY: all test test-pg test-mysql test-all test-db-pg test-db-mysql deb deploy clean

all: deb

# ── Integration tests ────────────────────────────────────────────────────────
#
# Each test target first (re)creates the matching DB and flips the Evolutions
# symlink, then runs the suite. Tests fork the JVM so -Dconfig.resource
# propagates to the test classpath (see build.sbt).

test: test-pg

test-pg: test-db-pg
	sbt -Dconfig.resource=test.conf test

test-mysql: test-db-mysql
	sbt -Dconfig.resource=test-mysql.conf test

test-all: test-pg test-mysql

test-db-pg:
	./test/create_test_db.sh pg

test-db-mysql:
	./test/create_test_db.sh mysql

# ── Packaging ────────────────────────────────────────────────────────────────

deb:
	sbt debian:packageBin

deploy: deb
	@echo "Adjust the deploy target for your environment."

clean:
	sbt clean
