#!/usr/bin/env bash
#
# Local equivalent of .github/workflows/ci.yml.
#
# The command below is the same one the CI composite action runs, so a green run here
# means a green run there: compile, tests, format check and static analysis, because
# Spotless and SpotBugs are bound to the verify phase. Only the image build needs the
# real runner, and it lives in quality.yml.
#
# Usage:
#   ./scripts/ci.sh              # everything
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

# Spotless and SpotBugs are bound to the verify phase in the pom, so the single
# command below is the whole check: compile, tests, format, static analysis. Running
# spotless:check separately here would only start a second Maven for a goal the build
# performs anyway.
echo "==> Build, test, format check and static analysis"
./mvnw "${MAVEN_ARGS[@]}" verify

echo
echo "CI steps passed locally. The image build is not part of this script on purpose -"
echo "run it when you need it:"
echo "  ./mvnw spotbugs:check spotless:check   # standalone, without the tests"
echo "  docker build -t financial-transactions:local ."