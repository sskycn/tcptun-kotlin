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
  -h, --help       Show this help

Environment:
  TCPTUN_GO_VERSION  tcptun-go tag or commit to include in this release (optional)
  TCPTUN_GO_DIR      tcptun-go checkout used when TCPTUN_GO_VERSION is unset
  TCPTUN_GO_REMOTE   tcptun-go remote used to resolve TCPTUN_GO_VERSION (default: origin)
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

run_bridge() {
	if [ -n "$tcptun_go_worktree" ]; then
		run env TCPTUN_GO_DIR="$tcptun_go_worktree" "$@"
	else
		run "$@"
	fi
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
tcptun_go_worktree=""
tcptun_go_worktree_root=""
tcptun_go_checkout=""
bridge_lock_changed=0
release_committed=0

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

restore_version_on_failure() {
	if [ "$release_committed" -eq 0 ]; then
		git restore -- gradle.properties bridge.lock >/dev/null 2>&1 || true
	fi
	if [ -n "$tcptun_go_worktree_root" ]; then
		git -C "$tcptun_go_checkout" worktree remove --force "$tcptun_go_worktree" >/dev/null 2>&1 || true
		rmdir "$tcptun_go_worktree_root" >/dev/null 2>&1 || true
	fi
}
trap restore_version_on_failure EXIT

run git fetch "$remote" --tags

local_head=$(git rev-parse HEAD) || die "failed to read local HEAD"
remote_head=$(git rev-parse "${remote}/${branch}") || die "failed to read ${remote}/${branch}"
[ "$local_head" = "$remote_head" ] || die "local ${branch} is not aligned with ${remote}/${branch}"

if [ -n "${TCPTUN_GO_VERSION:-}" ]; then
	tcptun_go_checkout="${TCPTUN_GO_DIR:-$repo_root/../tcptun-go}"
	[ -d "$tcptun_go_checkout" ] || die "tcptun-go checkout was not found at $tcptun_go_checkout"
	[ -z "$(git -C "$tcptun_go_checkout" status --porcelain --untracked-files=normal)" ] || \
		die "tcptun-go working tree is dirty; commit or stash changes first"

	tcptun_go_remote="${TCPTUN_GO_REMOTE:-origin}"
	[[ "$tcptun_go_remote" =~ ^[A-Za-z0-9._-]+$ ]] || \
		die "TCPTUN_GO_REMOTE must name a configured Git remote"
	[[ "$TCPTUN_GO_VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._/+:-]*$ ]] || \
		die "TCPTUN_GO_VERSION must be a Git tag, branch, or commit"

	run git -C "$tcptun_go_checkout" fetch --quiet --tags "$tcptun_go_remote"
	tcptun_go_commit=$(git -C "$tcptun_go_checkout" rev-parse --verify "${TCPTUN_GO_VERSION}^{commit}") || \
		die "could not resolve TCPTUN_GO_VERSION=$TCPTUN_GO_VERSION in $tcptun_go_checkout"

	[ -f bridge.lock ] || die "bridge.lock is required"
	bridge_lock_core_count=$(awk -F= '$1 == "coreCommit" { count++ } END { print count + 0 }' bridge.lock)
	[ "$bridge_lock_core_count" -eq 1 ] || die "bridge.lock must contain exactly one coreCommit property"
	current_bridge_core_commit=$(sed -n 's/^coreCommit=//p' bridge.lock)
	if [ "$current_bridge_core_commit" != "$tcptun_go_commit" ]; then
		perl -0pi -e 's/^coreCommit=.*$/coreCommit='"$tcptun_go_commit"'/m' bridge.lock
		bridge_lock_changed=1
	fi

	tcptun_go_worktree_root=$(mktemp -d "${TMPDIR:-/tmp}/tcptun-go-release.XXXXXX")
	tcptun_go_worktree="$tcptun_go_worktree_root/checkout"
	run git -C "$tcptun_go_checkout" worktree add --detach --quiet "$tcptun_go_worktree" "$tcptun_go_commit"
fi

if git rev-parse -q --verify "refs/tags/${version}" >/dev/null; then
	die "local tag ${version} already exists"
fi

if git ls-remote --exit-code --tags "$remote" "refs/tags/${version}" >/dev/null 2>&1; then
	die "remote tag ${version} already exists on ${remote}"
fi

run ./gradlew :app:requireReleaseSigning
run_bridge ./scripts/build-androidbridge.sh --verify-release
run_bridge ./scripts/build-androidbridge.sh
run ./gradlew :app:verifyAndroidBridge

bridge_status=$(git status --porcelain)
if [ "$bridge_lock_changed" -eq 1 ]; then
	[ "$bridge_status" = " M bridge.lock" ] || \
		die "Bridge rebuild changed unexpected files: $bridge_status"
else
	[ -z "$bridge_status" ] || \
		die "Bridge rebuild changed the working tree; review and commit the pinned AAR/lock before releasing"
fi

perl -0pi -e 's/^releaseVersionName=.*$/releaseVersionName='"${version_name}"'/m' gradle.properties
perl -0pi -e 's/^releaseVersionCode=.*$/releaseVersionCode='"${version_code}"'/m' gradle.properties

grep -Fx "releaseVersionName=${version_name}" gradle.properties >/dev/null || \
	die "failed to update releaseVersionName in gradle.properties"
grep -Fx "releaseVersionCode=${version_code}" gradle.properties >/dev/null || \
	die "failed to update releaseVersionCode in gradle.properties"

run ./gradlew qualityGate :app:verifyAndroidBridge :app:bundleRelease

if [ "$bridge_lock_changed" -eq 1 ]; then
	run git add gradle.properties bridge.lock
else
	run git add gradle.properties
fi
run git commit -m "chore: release ${version}"
run git tag -a "$version" -m "$version"
release_committed=1

if [ "$push_release" -eq 1 ]; then
	run git push "$remote" "$branch"
	run git push "$remote" "$version"
	printf 'release: pushed %s\n' "$version"
else
	printf 'release: created local commit and tag %s; push skipped\n' "$version"
fi
