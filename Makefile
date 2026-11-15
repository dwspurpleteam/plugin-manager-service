
IMAGE ?= ghcr.io/dwspurpleteam/pluginmanager
override TAG := $(shell git rev-parse HEAD)
PLATFORM ?= linux/amd64

.PHONY: image-build
image-build:
	git archive --format=tar $(TAG) | docker buildx build --platform $(PLATFORM) --load -t $(IMAGE):$(TAG) -f Dockerfile -

.PHONY: image-push
image-push:
	docker push $(IMAGE):$(TAG)
