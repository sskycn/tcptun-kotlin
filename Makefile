RELEASE_BRANCH ?= main
RELEASE_REMOTE ?= origin
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)

.PHONY: publish help

publish:
	@case "$(origin VERSION)" in command\ line|environment|environment\ override) ;; *) echo "VERSION is required, for example: make publish VERSION=v0.2.4" >&2; exit 2 ;; esac
	./scripts/release.sh "$(VERSION)" --branch "$(RELEASE_BRANCH)" --remote "$(RELEASE_REMOTE)"

help:
	@echo "Targets:"
	@echo "  make publish  Verify the bundled Bridge, test and bundle the app, then commit, tag, and push. Usage: make publish VERSION=v0.2.4"
