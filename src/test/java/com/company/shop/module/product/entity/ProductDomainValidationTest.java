package com.company.shop.module.product.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.company.shop.module.category.entity.Category;
import com.company.shop.module.product.exception.ProductDataInvalidException;
import com.company.shop.module.product.exception.ProductReviewRatingInvalidException;
import com.company.shop.module.product.exception.ProductStockInvalidException;
import com.company.shop.module.user.entity.User;

class ProductDomainValidationTest {

    @Test
    void restoreReservedStock_shouldAddExactQuantityAndRejectInvalidInput() {
        Category category = new Category("Name", "slug", "desc");
        Product product = new Product("Prod", "prod", "SKU", "desc", BigDecimal.ONE, 3, category);

        product.restoreReservedStock(2);

        assertThat(product.getStock()).isEqualTo(5);
        assertThatThrownBy(() -> product.restoreReservedStock(0))
                .isInstanceOf(ProductStockInvalidException.class);
    }

    @Test
    void updateRatings_shouldDefaultToZeroWhenAverageIsNull() {
        Category category = new Category("Name", "slug", "desc");
        Product product = new Product("Prod", "prod", "SKU", "desc", BigDecimal.ONE, 10, category);

        product.updateRatings(null, 0);

        assertThat(product.getAverageRating()).isEqualTo(0.0);
    }

    @Test
    void updateRatings_shouldClampAverageIntoDomainRange() {
        Category category = new Category("Name", "slug", "desc");
        Product product = new Product("Prod", "prod", "SKU", "desc", BigDecimal.ONE, 10, category);

        product.updateRatings(7.77, 5);
        assertThat(product.getAverageRating()).isEqualTo(5.0);

        product.updateRatings(-1.23, 5);
        assertThat(product.getAverageRating()).isEqualTo(0.0);
    }

    @Test
    void constructor_shouldRejectBlankName() {
        Category category = new Category("Name", "slug", "desc");

        assertThatThrownBy(() -> new Product(" ", "prod", "SKU", "desc", BigDecimal.ONE, 1, category))
                .isInstanceOf(ProductDataInvalidException.class);
    }

    @Test
    void constructor_shouldRejectZeroPrice() {
        Category category = new Category("Name", "slug", "desc");

        assertThatThrownBy(() -> new Product("Prod", "prod", "SKU", "desc", BigDecimal.ZERO, 1, category))
                .isInstanceOf(ProductDataInvalidException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    void constructor_shouldRejectPriceContainingFractionalGrosz() {
        Category category = new Category("Name", "slug", "desc");

        assertThatThrownBy(() -> new Product("Prod", "prod", "SKU", "desc", new BigDecimal("19.999"), 1, category))
                .isInstanceOf(ProductDataInvalidException.class)
                .hasMessageContaining("at most 2 fraction digits");
    }

    @Test
    void update_shouldRejectPriceOutsidePersistencePrecision() {
        Category category = new Category("Name", "slug", "desc");
        Product product = new Product("Prod", "prod", "SKU", "desc", new BigDecimal("19.99"), 1, category);

        assertThatThrownBy(() -> product.update("Prod", "prod", "SKU", "desc",
                new BigDecimal("10000000000.00"), 1, category))
                .isInstanceOf(ProductDataInvalidException.class)
                .hasMessageContaining("at most 10 integer digits");
    }

    @Test
    void review_shouldRejectInvalidRating() {
        Category category = new Category("Name", "slug", "desc");
        Product product = new Product("Prod", "prod", "SKU", "desc", BigDecimal.ONE, 10, category);
        User user = org.mockito.Mockito.mock(User.class);

        assertThatThrownBy(() -> new ProductReview(product, user, 6, "bad"))
                .isInstanceOf(ProductReviewRatingInvalidException.class);
    }

    @Test
    void review_shouldRejectNullUser() {
        Category category = new Category("Name", "slug", "desc");
        Product product = new Product("Prod", "prod", "SKU", "desc", BigDecimal.ONE, 10, category);

        assertThatThrownBy(() -> new ProductReview(product, null, 5, "ok"))
                .isInstanceOf(ProductDataInvalidException.class)
                .hasMessageContaining("Review user is required");
    }

    @Test
    void review_shouldRejectNullProduct() {
        User user = org.mockito.Mockito.mock(User.class);

        assertThatThrownBy(() -> new ProductReview(null, user, 5, "ok"))
                .isInstanceOf(ProductDataInvalidException.class)
                .hasMessageContaining("Review product is required");
    }
    @Test
    void review_shouldTrimCommentAndConvertBlankToNull() {
        Category category = new Category("Name", "slug", "desc");
        Product product = new Product("Prod", "prod", "SKU", "desc", BigDecimal.ONE, 10, category);
        User user = org.mockito.Mockito.mock(User.class);

        ProductReview review = new ProductReview(product, user, 5, "  hello world  ");
        assertThat(review.getComment()).isEqualTo("hello world");

        review.update(5, "   ");
        assertThat(review.getComment()).isNull();
    }

}
