package com.company.shop.module.product.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ProductCreateDTOValidationTest {

	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		validator = Validation.buildDefaultValidatorFactory().getValidator();
	}

	@Test
	void imageUrls_shouldAcceptUrlWithExactly512Characters() {
		assertThat(validator.validate(validDto(List.of("a".repeat(512))))).isEmpty();
	}

	@Test
	void imageUrls_shouldRejectUrlWith513Characters() {
		assertThat(validator.validate(validDto(List.of("a".repeat(513)))))
				.singleElement()
				.satisfies(violation -> assertThat(violation.getPropertyPath().toString())
						.isEqualTo("imageUrls[0].<list element>"));
	}

	@Test
	void imageUrls_shouldAcceptMultipleIndividuallyValidUrlsWithoutCollectionMaximum() {
		List<String> imageUrls = IntStream.range(0, 100)
				.mapToObj(index -> "https://cdn.example.com/product-" + index + ".jpg")
				.toList();

		assertThat(validator.validate(validDto(imageUrls))).isEmpty();
	}

	@Test
	void imageUrls_shouldRejectNullElement() {
		assertThat(validator.validate(validDto(java.util.Arrays.asList((String) null))))
				.singleElement()
				.satisfies(violation -> assertThat(violation.getPropertyPath().toString())
						.isEqualTo("imageUrls[0].<list element>"));
	}

	@Test
	void imageUrls_shouldContinueToAcceptNullListEmptyListAndBlankElements() {
		assertThat(validator.validate(validDto(null))).isEmpty();
		assertThat(validator.validate(validDto(List.of()))).isEmpty();
		assertThat(validator.validate(validDto(List.of("")))).isEmpty();
	}

	private ProductCreateDTO validDto(List<String> imageUrls) {
		return new ProductCreateDTO(
				"Product",
				"SKU-1",
				"Description",
				new BigDecimal("10.00"),
				1,
				UUID.randomUUID(),
				imageUrls);
	}
}
