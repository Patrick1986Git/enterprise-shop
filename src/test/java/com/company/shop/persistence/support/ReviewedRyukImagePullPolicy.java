package com.company.shop.persistence.support;

import org.testcontainers.images.AbstractImagePullPolicy;
import org.testcontainers.images.ImageData;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

public final class ReviewedRyukImagePullPolicy extends AbstractImagePullPolicy {

    private final ImagePullPolicy delegate;

    public ReviewedRyukImagePullPolicy() {
        this(null);
    }

    ReviewedRyukImagePullPolicy(ImagePullPolicy delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean shouldPull(DockerImageName imageName) {
        boolean pull = delegate == null ? super.shouldPull(imageName) : delegate.shouldPull(imageName);
        if (pull && imageName.asCanonicalNameString().startsWith("local/enterprise-shop-ryuk:sha256-")) {
            throw new IllegalStateException("The reviewed local Ryuk image is missing. From the repository root run "
                    + "python3 scripts/build-ryuk-candidate.py --no-analysis before ./mvnw clean verify. "
                    + "See docs/operations/local-development.md; this image is built locally and cannot be pulled.");
        }
        return pull;
    }

    @Override
    protected boolean shouldPullCached(DockerImageName imageName, ImageData localImageData) {
        return false;
    }
}
