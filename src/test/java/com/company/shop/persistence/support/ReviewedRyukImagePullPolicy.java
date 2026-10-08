package com.company.shop.persistence.support;

import org.testcontainers.images.DefaultPullPolicy;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

public final class ReviewedRyukImagePullPolicy implements ImagePullPolicy {

    private final ImagePullPolicy delegate;

    public ReviewedRyukImagePullPolicy() {
        this(new DefaultPullPolicy());
    }

    ReviewedRyukImagePullPolicy(ImagePullPolicy delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean shouldPull(DockerImageName imageName) {
        boolean pull = delegate.shouldPull(imageName);
        if (pull && imageName.asCanonicalName().startsWith("local/enterprise-shop-ryuk:sha256-")) {
            throw new IllegalStateException("The reviewed local Ryuk image is missing. From the repository root run "
                    + "python3 scripts/build-ryuk-candidate.py --no-analysis before ./mvnw clean verify. "
                    + "See docs/operations/local-development.md; this image is built locally and cannot be pulled.");
        }
        return pull;
    }
}
