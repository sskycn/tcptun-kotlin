#!/usr/bin/env bash
set -euo pipefail

usage() {
	cat <<'EOF'
Usage:
  scripts/release.sh VERSION [options]

Examples:
  scripts/release.sh v0.2.4
  scripts/release.sh 0.2.4 --no-push

Options:
  --branch NAME    Release branch to require before tagging (default: main)
  --remote NAME    Git remote to push to (default: origin)
  --no-push        Commit and tag locally without pushing
  --skip-tests     Skip the Android Bridge build and Gradle quality gates
  -h, --help       Show this help
EOF
}

die() {
	printf 'release: %s\n' "$*" >&2
	exit 1
}

run() {
	printf '+ %s\n' "$*"
	"$@"
}

normalize_version() {
	local input=$1
	if [[ $input =~ ^[0-9]+[.][0-9]+[.][0-9]+([-][0-9A-Za-z.-]+)?([+][0-9A-Za-z.-]+)?$ ]]; then
		printf 'v%s\n' "$input"
		return 0
	fi
	printf '%s\n' "$input"
}

version=""
branch="main"
remote="origin"
push_release=1
run_tests=1

while [ "$#" -gt 0 ]; do
	case "$1" in
		--branch)
			shift
			[ "$#" -gt 0 ] || die "--branch requires a value"
			branch=$1
			;;
		--remote)
			shift
			[ "$#" -gt 0 ] || die "--remote requires a value"
			remote=$1
			;;
		--no-push)
			push_release=0
			;;
		--skip-tests)
			run_tests=0
			;;
		-h|--help)
			usage
			exit 0
			;;
		-*)
			die "unknown option: $1"
			;;
		*)
			[ -z "$version" ] || die "version specified more than once"
			version=$(normalize_version "$1")
			;;
	esac
	shift
done

[ -n "$version" ] || {
	usage >&2
	exit 2
}

[ "${ALLOW_UNPINNED_BRIDGE:-0}" != "1" ] || \
	die "ALLOW_UNPINNED_BRIDGE is forbidden for releases"

[[ $version =~ ^v([0-9]+)[.]([0-9]+)[.]([0-9]+)([-][0-9A-Za-z.-]+)?([+][0-9A-Za-z.-]+)?$ ]] || \
	die "version must look like vX.Y.Z, for example v0.2.4"

major=$((10#${BASH_REMATCH[1]}))
minor=$((10#${BASH_REMATCH[2]}))
patch=$((10#${BASH_REMATCH[3]}))
[ "$minor" -le 999 ] || die "minor version must be no greater than 999"
[ "$patch" -le 999 ] || die "patch version must be no greater than 999"

version_name=${version#v}
version_code=$((major * 1000000 + minor * 1000 + patch))
[ "$version_code" -ge 1 ] && [ "$version_code" -le 2100000000 ] || \
	die "version produces an invalid Android versionCode: $version_code"

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "${script_dir}/.." && pwd)
cd "$repo_root"

command -v git >/dev/null 2>&1 || die "git is required"
command -v perl >/dev/null 2>&1 || die "perl is required"
[ -x ./gradlew ] || die "gradlew not found or not executable"
[ -f app/build.gradle.kts ] || die "app/build.gradle.kts not found; run from the tcptun-kotlin repository"
[ -f gradle.properties ] || die "gradle.properties not found; run from the tcptun-kotlin repository"

current_branch=$(git rev-parse --abbrev-ref HEAD) || die "failed to read current branch"
[ "$current_branch" = "$branch" ] || die "current branch is $current_branch, expected $branch"

[ -z "$(git status --porcelain)" ] || die "working tree is dirty; commit or stash changes first"

run ./scripts/build-androidbridge.sh --verify-lock

run git fetch "$remote" --tags

local_head=$(git rev-parse HEAD) || die "failed to read local HEAD"
remote_head=$(git rev-parse "${remote}/${branch}") || die "failed to read ${remote}/${branch}"
[ "$local_head" = "$remote_head" ] || die "local ${branch} is not aligned with ${remote}/${branch}"

if git rev-parse -q --verify "refs/tags/${version}" >/dev/null; then
	die "local tag ${version} already exists"
fi

if git ls-remote --exit-code --tags "$remote" "refs/tags/${version}" >/dev/null 2>&1; then
	die "remote tag ${version} already exists on ${remote}"
fi

perl -0pi -e 's/^releaseVersionName=.*$/releaseVersionName='"${version_name}"'/m' gradle.properties
perl -0pi -e 's/^releaseVersionCode=.*$/releaseVersionCode='"${version_code}"'/m' gradle.properties

grep -Fx "releaseVersionName=${version_name}" gradle.properties >/dev/null || \
	die "failed to update releaseVersionName in gradle.properties"
grep -Fx "releaseVersionCode=${version_code}" gradle.properties >/dev/null || \
	die "failed to update releaseVersionCode in gradle.properties"

if [ "$run_tests" -eq 1 ]; then
	run ./scripts/build-androidbridge.sh
	run ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:bundleRelease
fi

run git add gradle.properties
run git commit -m "chore: release ${version}"
run git tag -a "$version" -m "$version"

if [ "$push_release" -eq 1 ]; then
	run git push "$remote" "$branch"
	run git push "$remote" "$version"
	printf 'release: pushed %s\n' "$version"
else
	printf 'release: created local commit and tag %s; push skipped\n' "$version"
fi
