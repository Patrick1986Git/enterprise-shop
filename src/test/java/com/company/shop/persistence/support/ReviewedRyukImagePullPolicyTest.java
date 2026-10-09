package com.company.shop.persistence.support;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewedRyukImagePullPolicyTest {

    private static final DockerImageName CANDIDATE = DockerImageName.parse("local/enterprise-shop-ryuk:sha256-" + "a".repeat(64));

    @Test
    void missingCandidate_shouldExplainLocalPreparationInsteadOfPulling() {
        var policy = new ReviewedRyukImagePullPolicy(image -> true);
        assertThatThrownBy(() -> policy.shouldPull(CANDIDATE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("python3 scripts/build-ryuk-candidate.py --no-analysis")
                .hasMessageContaining("cannot be pulled");
    }

    @Test
    void cachedCandidate_shouldUseTheLocalImage() {
        var policy = new ReviewedRyukImagePullPolicy(image -> false);
        assertThat(policy.shouldPull(CANDIDATE)).isFalse();
    }

    @Test
    void otherImages_shouldRetainDefaultPullBehavior() {
        DockerImageName postgres = DockerImageName.parse("postgres:18-alpine");
        assertThat(new ReviewedRyukImagePullPolicy(image -> true).shouldPull(postgres)).isTrue();
        assertThat(new ReviewedRyukImagePullPolicy(image -> false).shouldPull(postgres)).isFalse();
    }
}
