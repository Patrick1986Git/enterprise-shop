package com.company.shop.module.category.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO używane do tworzenia nowej kategorii.
 */
public class CategoryCreateDTO {

	@NotBlank(message = "{validation.category.name.required}")
	@Size(max = 150, message = "{validation.category.name.size}")
	private String name;

	@Size(max = 500, message = "{validation.category.description.size}")
	private String description;

	// UUID kategorii nadrzędnej (opcjonalne)
	private UUID parentId;

	// Pusty konstruktor wymagany przez bibliotekę Jackson (Spring)
	public CategoryCreateDTO() {
	}

	// Konstruktor argumentowy ułatwiający testowanie
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