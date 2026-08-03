#!/usr/bin/env bash
#
# Resolves the version of a beta release from git history.
#
# The source of truth is the git tags plus the Conventional Commits made since the most recent one,
# not a literal in the build script. The result is exported for Gradle to read from the environment;
# nothing is ever written back into the repository, so CI never pushes a version bump commit.
#
# This needs the full history and every tag, which means `actions/checkout` with `fetch-depth: 0`.
# A shallow clone carries no tags and a truncated commit list: tag discovery would silently find
# nothing and every run would resolve the same version.
#
# Writes FOLIUM_VERSION_NAME, FOLIUM_VERSION_CODE, FOLIUM_BUMP_REASON and FOLIUM_COMMIT_RANGE to
# ${GITHUB_ENV} when running under Actions, and prints the same values either way.

set -euo pipefail

readonly BUILD_FILE="app/build.gradle.kts"
readonly TAG_PATTERN='v[0-9]*.[0-9]*.[0-9]*-beta.[0-9]*'

# Reads a literal out of the build script. These committed values are the local-development
# baseline and the fallback used when no release tag exists yet.
committed_literal() {
    local pattern="$1"
    sed -n "s/^val ${pattern}\$/\\1/p" "${BUILD_FILE}" | head -n 1
}

# Classifies the commits in a range as major, minor or patch according to Conventional Commits:
# a `!` marker after the type or a `BREAKING CHANGE:` footer means major, any `feat:` means minor,
# anything else is a patch.
#
# The subjects and bodies are captured into variables rather than piped straight into grep, because
# `grep -q` exits on its first match and would leave `git log` writing into a closed pipe; under
# `set -o pipefail` that turns a successful match into a failed command.
classify_bump() {
    local range="$1"
    local subjects bodies

    subjects=$(git log --format=%s "${range}")
    bodies=$(git log --format=%B "${range}")

    if printf '%s\n' "${subjects}" | grep -qE '^[a-zA-Z]+(\([^)]*\))?!:' ||
        printf '%s\n' "${bodies}" | grep -qE '^BREAKING[ -]CHANGE:'; then
        echo "major"
    elif printf '%s\n' "${subjects}" | grep -qE '^feat(\([^)]*\))?:'; then
        echo "minor"
    else
        echo "patch"
    fi
}

apply_bump() {
    local version="$1" bump="$2"
    local major minor patch

    IFS='.' read -r major minor patch <<<"${version}"

    case "${bump}" in
    major) echo "$((major + 1)).0.0" ;;
    minor) echo "${major}.$((minor + 1)).0" ;;
    patch) echo "${major}.${minor}.$((patch + 1))" ;;
    none) echo "${major}.${minor}.${patch}" ;;
    *)
        echo "unknown bump kind: ${bump}" >&2
        exit 1
        ;;
    esac
}

committed_version_name=$(committed_literal 'committedVersionName = "\([^"]*\)"')
committed_version_code=$(committed_literal 'committedVersionCode = \([0-9]\{1,\}\)')

if [ -z "${committed_version_name}" ] || [ -z "${committed_version_code}" ]; then
    echo "::error::Could not read committedVersionName/committedVersionCode from ${BUILD_FILE}." >&2
    exit 1
fi

base_tag=$(git describe --tags --abbrev=0 --match "${TAG_PATTERN}" 2>/dev/null || true)

if [ -n "${base_tag}" ]; then
    base_version=${base_tag#v}
    base_version=${base_version%%-*}
    commit_range="${base_tag}..HEAD"
    commits_since=$(git rev-list --count "${commit_range}")

    if [ "${commits_since}" -eq 0 ]; then
        bump="none"
        bump_reason="no commits since ${base_tag}; version held at ${base_version}"
    else
        bump=$(classify_bump "${commit_range}")
        bump_reason="${bump} bump from ${base_version} (${commits_since} commit(s) since ${base_tag})"
    fi
else
    base_version="${committed_version_name}"
    commit_range="(full history)"
    commits_since=$(git rev-list --count HEAD)
    bump="none"
    bump_reason="no release tag found; starting from the committed versionName ${base_version}"
fi

version_name=$(apply_bump "${base_version}" "${bump}")

# versionCode must never decrease or Android refuses to install the new APK over the old one. The
# commit count is monotonic on `main`, deterministic and reproducible from any clone, so it needs no
# state outside git. The committed literal is only a floor for the degenerate case where it was
# hand-raised above the commit count.
commit_count=$(git rev-list --count HEAD)
version_code="${commit_count}"

if [ "${committed_version_code}" -ge "${commit_count}" ]; then
    version_code=$((committed_version_code + 1))
    echo "::warning::committedVersionCode (${committed_version_code}) is not below the commit count (${commit_count}); using ${version_code} so the sequence never goes backwards."
fi

printf 'base tag:       %s\n' "${base_tag:-<none>}"
printf 'base version:   %s\n' "${base_version}"
printf 'commit range:   %s\n' "${commit_range}"
printf 'commits since:  %s\n' "${commits_since}"
printf 'bump:           %s\n' "${bump}"
printf 'bump reason:    %s\n' "${bump_reason}"
printf 'versionName:    %s\n' "${version_name}"
printf 'versionCode:    %s\n' "${version_code}"

if [ -n "${GITHUB_ENV:-}" ]; then
    {
        echo "FOLIUM_VERSION_NAME=${version_name}"
        echo "FOLIUM_VERSION_CODE=${version_code}"
        echo "FOLIUM_BUMP_REASON=${bump_reason}"
        echo "FOLIUM_COMMIT_RANGE=${commit_range}"
    } >>"${GITHUB_ENV}"
fi
