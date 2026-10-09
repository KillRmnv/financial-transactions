#!/usr/bin/env bash
#
# Local equivalent of .github/workflows/ci.yml.
#
# The Maven steps below are the same ones the CI composite action runs, so a green run
# here means a green run there. Anything that needs the real runner (static analysis,
# image build) lives in quality.yml and is deliberately not part of this script.
#
# Usage:
#   ./scripts/ci.sh              # format check + tests
#   ./scripts/ci.sh --fix        # reformat the code instead of failing
#   ./scripts/ci.sh -Dtest=Foo   # pass extra arguments through to Maven

set -euo pipefail

cd "$(dirname "$0")/.."

# The pom targets Java 25; the JDKs installed here are 21 and 27, and javac 21 refuses
# --release 25. Pin JAVA_HOME so a bare `mvn` or a stray shell default cannot break it.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ]; then
	export JAVA_HOME=/usr/lib/jvm/java-27-openjdk
fi

MAVEN_ARGS=(-B -ntp)

for arg in "$@"; do
	case "$arg" in
	--fix)
		echo "==> Applying the Spring Java Format instead of checking it"
		./mvnw "${MAVEN_ARGS[@]}" spotless:apply
		exit 0
		;;
	*)
		MAVEN_ARGS+=("$arg")
		;;
	esac
done

echo "==> JDK: $(java -version 2>&1 | head -1)"

echo "==> Format check"
./mvnw "${MAVEN_ARGS[@]}" spotless:check

echo "==> Build and test"
./mvnw "${MAVEN_ARGS[@]}" verify

echo
echo "CI steps passed locally. Static analysis and the image build are not part of this"
echo "script on purpose - run them when you need them:"
echo "  ./mvnw spotbugs:check spotless:check"
echo "  docker build -t financial-transactions:local ."