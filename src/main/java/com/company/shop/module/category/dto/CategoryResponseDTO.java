package com.company.shop.module.category.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO wyjściowe dla kategorii. Niemutowalne (immutable) dla zapewnienia
 * spójności danych wysyłanych do klienta.
 */
@Schema(description = "Response payload containing category details.")
public class CategoryResponseDTO {

	@Schema(description = "Unique category identifier.", example = "11111111-1111-1111-1111-111111111111", accessMode = Schema.AccessMode.READ_ONLY)
	private final UUID id;
	@Schema(description = "Category display name.", example = "Electronics", accessMode = Schema.AccessMode.READ_ONLY)
	private final String name;
	@Schema(description = "URL-friendly category identifier.", example = "electronics", accessMode = Schema.AccessMode.READ_ONLY)
	private final String slug;
	@Schema(description = "Category description.", example = "Devices, accessories, and related products.", accessMode = Schema.AccessMode.READ_ONLY)
	private final String description;
	@Schema(description = "Display name of the parent category, when present.", example = "Home", accessMode = Schema.AccessMode.READ_ONLY)
	private final String parentName;

	public CategoryResponseDTO(UUID id, String name, String slug, String description, String parentName) { this.id = id; this.name = name; this.slug = slug; this.description = description; this.parentName = parentName; }
	public UUID getId() { return id; }
	public String getName() { return name; }
	public String getSlug() { return slug; }
	public String getDescription() { return description; }
	public String getParentName() { return parentName; }
}
