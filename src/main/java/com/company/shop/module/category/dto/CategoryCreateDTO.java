package com.company.shop.module.category.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO używane do tworzenia nowej kategorii.
 */
@Schema(description = "Request payload for creating a category.")
public class CategoryCreateDTO {

	@Schema(description = "Category display name.", example = "Electronics", maxLength = 150, requiredMode = Schema.RequiredMode.REQUIRED)
	@NotBlank(message = "{validation.category.name.required}")
	@Size(max = 150, message = "{validation.category.name.size}")
	private String name;

	@Schema(description = "Optional category description.", example = "Devices, accessories, and related products.", maxLength = 500, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
	@Size(max = 500, message = "{validation.category.description.size}")
	private String description;

	@Schema(description = "Optional parent category identifier for nested categories.", example = "11111111-1111-1111-1111-111111111111", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
	private UUID parentId;

	public CategoryCreateDTO() {
	}

	public CategoryCreateDTO(String name, String description, UUID parentId) {
		this.name = name;
		this.description = description;
		this.parentId = parentId;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public UUID getParentId() {
		return parentId;
	}
}
