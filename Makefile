# Copyright © Manetu, Inc.  All rights reserved

NAME=manetu-benchmark-tool
BINDIR ?= /usr/local/bin
OUTPUT=target/$(NAME)
SHELL=/bin/bash -o pipefail

export PROJECT_NAME := benchmark-tool
export IMAGE_REPOSITORY :=  registry.gitlab.com/manetu/users/$(USER)/$(PROJECT_NAME)/sandbox:latest

SRCS += $(shell find src -type f)

COVERAGE_THRESHOLD = 98
COVERAGE_EXCLUSION += "manetu.benchmark-tool.main"

all: scan bin

bin: $(OUTPUT)

scan:
	lein cljfmt check
	lein bikeshed -m 120 -n false
	#lein kibit
	lein eastwood

.PHONY: test
test:
	lein cloverage --fail-threshold $(COVERAGE_THRESHOLD) $(patsubst %,-e %, $(COVERAGE_EXCLUSION)) | perl -pe 's/\e\[?.*?[\@-~]//g'

$(OUTPUT): $(SRCS) Makefile project.clj
	@lein bin

$(PREFIX)$(BINDIR):
	mkdir -p $@

install: $(OUTPUT) $(PREFIX)$(BINDIR)
	cp $(OUTPUT) $(PREFIX)$(BINDIR)

.PHONY: release
release:
	docker build $(DOCKER_OPTS) -t $(IMAGE_REPOSITORY) \
		--build-arg GITLAB_TOKEN_TYPE="Private-Token" \
		--build-arg GITLAB_TOKEN_READ_REPOSITORY="$(GITLAB_TOKEN_READ_REPOSITORY)" \
		--build-arg GITLAB_TOKEN_VALUE="$(GITLAB_TOKEN)" \
		--build-arg GOPRIVATE="$(GOPRIVATE)" \
		.
	docker push $(IMAGE_REPOSITORY)

clean:
	@echo "Cleaning up.."
	@lein clean
	-@rm -rf target
	-@rm -f *~

