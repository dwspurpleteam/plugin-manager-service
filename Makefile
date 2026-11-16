
IMAGE ?= ghcr.io/dwspurpleteam/pluginmanager
override TAG := $(shell git rev-parse HEAD)
PLATFORM ?= linux/amd64
DOCKERFILE ?= Dockerfile
CONTEXT ?=

.PHONY: image-build
image-build:
ifeq ($(strip $(CONTEXT)),)
	git archive --format=tar $(TAG) | docker buildx build --platform $(PLATFORM) --load -t $(IMAGE):$(TAG) -f $(DOCKERFILE) -
else
	docker buildx build --platform $(PLATFORM) --load -t $(IMAGE):$(TAG) -f $(DOCKERFILE) $(CONTEXT)
endif

.PHONY: image-push
image-push:
	docker push $(IMAGE):$(TAG)
