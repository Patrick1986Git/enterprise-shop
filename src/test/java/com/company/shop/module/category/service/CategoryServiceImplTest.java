package com.company.shop.module.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.company.shop.module.category.dto.CategoryCreateDTO;
import com.company.shop.module.category.dto.CategoryResponseDTO;
import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.exception.CategoryAlreadyExistsException;
import com.company.shop.module.category.exception.CategoryHierarchyException;
import com.company.shop.module.category.exception.CategoryNotFoundException;
import com.company.shop.module.category.exception.CategorySlugAlreadyExistsException;
import com.company.shop.module.category.mapper.CategoryMapper;
import com.company.shop.module.category.repository.CategoryRepository;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

	@Mock
	private CategoryRepository repo;

	@Mock
	private CategoryMapper mapper;

	private CategoryServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new CategoryServiceImpl(repo, mapper);
	}

	private void stubMapperToDto() {
		when(mapper.toDto(any(Category.class))).thenAnswer(invocation -> {
			Category category = invocation.getArgument(0);
			String parentName = category.getParent() != null ? category.getParent().getName() : null;
			return new CategoryResponseDTO(category.getId(), category.getName(), category.getSlug(),
					category.getDescription(), parentName);
		});
	}

	@Test
	void findAssignableCategory_shouldReturnRepositoryResult() {
		UUID categoryId = UUID.randomUUID();
		Category category = new Category("Technology", "technology", "Products");
		when(repo.findById(categoryId)).thenReturn(Optional.of(category));

		Optional<Category> result = service.findAssignableCategory(categoryId);

		assertThat(result).containsSame(category);
		verify(repo).findById(categoryId);
	}

	@Nested
	class CreateTests {

		@Test
		void create_shouldGenerateSlugFromNameWithPolishCharsWhitespaceAndPunctuation() {
			stubMapperToDto();
			CategoryCreateDTO dto = new CategoryCreateDTO(" Żółć   RTV/AGD ", "desc", null);
			when(repo.existsByName(dto.getName())).thenReturn(false);
			when(repo.existsBySlug("zoc-rtvagd")).thenReturn(false);
			when(repo.saveAndFlush(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

			CategoryResponseDTO result = service.create(dto);

			assertThat(result.getSlug()).isEqualTo("zoc-rtvagd");

			ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
			verify(repo).saveAndFlush(captor.capture());
			assertThat(captor.getValue().getSlug()).isEqualTo("zoc-rtvagd");
			assertThat(captor.getValue().getName()).isEqualTo(dto.getName());
		}

		@Test
		void create_shouldThrowWhenNameAlreadyExists() {
			CategoryCreateDTO dto = new CategoryCreateDTO("Electronics", "desc", null);
			when(repo.existsByName(dto.getName())).thenReturn(true);

			assertThatThrownBy(() -> service.create(dto)).isInstanceOfSatisfying(CategoryAlreadyExistsException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_ALREADY_EXISTS"));

			verify(repo).existsByName(dto.getName());
			verify(repo, never()).saveAndFlush(any(Category.class));
		}

		@Test
		void create_shouldThrowWhenGeneratedSlugAlreadyExists() {
			CategoryCreateDTO dto = new CategoryCreateDTO("New Category", "desc", null);
			when(repo.existsByName(dto.getName())).thenReturn(false);
			when(repo.existsBySlug("new-category")).thenReturn(true);

			assertThatThrownBy(() -> service.create(dto)).isInstanceOfSatisfying(
					CategorySlugAlreadyExistsException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_SLUG_ALREADY_EXISTS"));

			verify(repo).existsBySlug("new-category");
			verify(repo, never()).saveAndFlush(any(Category.class));
		}

		@Test
		void create_shouldThrowWhenParentNotFound() {
			UUID missingParentId = UUID.randomUUID();
			CategoryCreateDTO dto = new CategoryCreateDTO("Electronics", "desc", missingParentId);

			when(repo.existsByName(dto.getName())).thenReturn(false);
			when(repo.existsBySlug("electronics")).thenReturn(false);
			when(repo.findById(missingParentId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.create(dto)).isInstanceOfSatisfying(CategoryNotFoundException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_NOT_FOUND"));

			verify(repo).findById(missingParentId);
			verify(repo, never()).saveAndFlush(any(Category.class));
		}

		@Test
		void create_shouldAssignParentWhenParentExists() {
			stubMapperToDto();
			UUID parentId = UUID.randomUUID();
			Category parent = new Category("Parent", "parent", "desc");
			CategoryCreateDTO dto = new CategoryCreateDTO("Child Category", "child-desc", parentId);

			when(repo.existsByName(dto.getName())).thenReturn(false);
			when(repo.existsBySlug("child-category")).thenReturn(false);
			when(repo.findById(parentId)).thenReturn(Optional.of(parent));
			when(repo.saveAndFlush(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

			CategoryResponseDTO result = service.create(dto);

			assertThat(result.getParentName()).isEqualTo("Parent");

			ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
			verify(repo).saveAndFlush(captor.capture());
			assertThat(captor.getValue().getParent()).isEqualTo(parent);
		}

		@Test
		void create_shouldMapDataIntegrityViolationContainingSlugToCategorySlugAlreadyExistsException() {
			CategoryCreateDTO dto = new CategoryCreateDTO("Video Cards", "desc", null);

			when(repo.existsByName(dto.getName())).thenReturn(false);
			when(repo.existsBySlug("video-cards")).thenReturn(false);
			when(repo.saveAndFlush(any(Category.class))).thenThrow(
					new DataIntegrityViolationException("duplicate key", new RuntimeException("Unique index on slug")));

			assertThatThrownBy(() -> service.create(dto))
					.isInstanceOfSatisfying(CategorySlugAlreadyExistsException.class, ex -> {
						assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_SLUG_ALREADY_EXISTS");
						assertThat(ex.getMessage()).contains("video-cards");
					});
		}

		@Test
		void create_shouldMapGenericDataIntegrityViolationToCategoryAlreadyExistsException() {
			CategoryCreateDTO dto = new CategoryCreateDTO("Office", "desc", null);

			when(repo.existsByName(dto.getName())).thenReturn(false);
			when(repo.existsBySlug("office")).thenReturn(false);
			when(repo.saveAndFlush(any(Category.class))).thenThrow(
					new DataIntegrityViolationException("duplicate key", new RuntimeException("Unique index on name")));

			assertThatThrownBy(() -> service.create(dto)).isInstanceOfSatisfying(CategoryAlreadyExistsException.class,
					ex -> {
						assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_ALREADY_EXISTS");
						assertThat(ex.getMessage()).contains("Office");
					});
		}
	}

	@Nested
	class UpdateTests {

		@Test
		void update_shouldGenerateSlugFromUpdatedName() {
			stubMapperToDto();
			UUID id = UUID.randomUUID();
			Category existing = new Category("Old Name", "old-name", "desc");
			CategoryCreateDTO dto = new CategoryCreateDTO("  Home   Audio  ", "new-desc", null);

			when(repo.findById(id)).thenReturn(Optional.of(existing));
			when(repo.existsByNameAndIdNot(dto.getName(), id)).thenReturn(false);
			when(repo.existsBySlugAndIdNot("home-audio", id)).thenReturn(false);
			when(repo.saveAndFlush(existing)).thenReturn(existing);

			CategoryResponseDTO result = service.update(id, dto);

			assertThat(existing.getSlug()).isEqualTo("home-audio");
			assertThat(existing.getDescription()).isEqualTo("new-desc");
			assertThat(result.getSlug()).isEqualTo("home-audio");
			verify(repo).saveAndFlush(existing);
		}

		@Test
		void update_shouldAllowSameNameAndSlugForSameCategory() {
			stubMapperToDto();
			UUID id = UUID.randomUUID();
			Category existing = new Category("Electronics", "electronics", "desc");
			CategoryCreateDTO dto = new CategoryCreateDTO("Electronics", "updated-desc", null);

			when(repo.findById(id)).thenReturn(Optional.of(existing));
			when(repo.existsByNameAndIdNot(dto.getName(), id)).thenReturn(false);
			when(repo.existsBySlugAndIdNot("electronics", id)).thenReturn(false);
			when(repo.saveAndFlush(existing)).thenReturn(existing);

			CategoryResponseDTO result = service.update(id, dto);

			assertThat(result.getName()).isEqualTo("Electronics");
			assertThat(result.getSlug()).isEqualTo("electronics");
			assertThat(result.getDescription()).isEqualTo("updated-desc");
			verify(repo).saveAndFlush(existing);
		}

		@Test
		void update_shouldThrowWhenNameAlreadyExistsForAnotherCategory() {
			UUID id = UUID.randomUUID();
			Category existing = new Category("Old Name", "old-name", "desc");
			CategoryCreateDTO dto = new CategoryCreateDTO("Electronics", "desc", null);

			when(repo.findById(id)).thenReturn(Optional.of(existing));
			when(repo.existsByNameAndIdNot(dto.getName(), id)).thenReturn(true);

			assertThatThrownBy(() -> service.update(id, dto)).isInstanceOfSatisfying(
					CategoryAlreadyExistsException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_ALREADY_EXISTS"));

			verify(repo, never()).saveAndFlush(any(Category.class));
		}

		@Test
		void update_shouldThrowWhenGeneratedSlugAlreadyExistsForAnotherCategory() {
			UUID id = UUID.randomUUID();
			Category existing = new Category("Old Name", "old-name", "desc");
			CategoryCreateDTO dto = new CategoryCreateDTO("New Name", "desc", null);

			when(repo.findById(id)).thenReturn(Optional.of(existing));
			when(repo.existsByNameAndIdNot(dto.getName(), id)).thenReturn(false);
			when(repo.existsBySlugAndIdNot("new-name", id)).thenReturn(true);

			assertThatThrownBy(() -> service.update(id, dto)).isInstanceOfSatisfying(
					CategorySlugAlreadyExistsException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_SLUG_ALREADY_EXISTS"));

			verify(repo, never()).saveAndFlush(any(Category.class));
		}

		@Test
		void update_shouldThrowWhenParentNotFound() {
			UUID id = UUID.randomUUID();
			UUID missingParentId = UUID.randomUUID();
			Category existing = new Category("Old Name", "old-name", "desc");
			CategoryCreateDTO dto = new CategoryCreateDTO("New Name", "desc", missingParentId);

			when(repo.findById(id)).thenReturn(Optional.of(existing));
			when(repo.existsByNameAndIdNot(dto.getName(), id)).thenReturn(false);
			when(repo.existsBySlugAndIdNot("new-name", id)).thenReturn(false);
			when(repo.findById(missingParentId)).thenReturn(Optional.empty());

			assertThatThrownBy(() -> service.update(id, dto)).isInstanceOfSatisfying(CategoryNotFoundException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_NOT_FOUND"));

			verify(repo, never()).saveAndFlush(any(Category.class));
		}

		@Test
		void update_shouldAssignParentWhenHierarchyIsValid() {
			stubMapperToDto();
			UUID id = UUID.randomUUID();
			UUID parentId = UUID.randomUUID();

			Category existing = new Category("Old Name", "old-name", "desc");
			Category parent = new Category("Parent", "parent", "desc");
			CategoryCreateDTO dto = new CategoryCreateDTO("Old Name", "desc", parentId);

			when(repo.findById(id)).thenReturn(Optional.of(existing));
			when(repo.existsByNameAndIdNot(dto.getName(), id)).thenReturn(false);
			when(repo.existsBySlugAndIdNot("old-name", id)).thenReturn(false);
			when(repo.findById(parentId)).thenReturn(Optional.of(parent));
			when(repo.saveAndFlush(existing)).thenReturn(existing);

			CategoryResponseDTO result = service.update(id, dto);

			assertThat(existing.getParent()).isEqualTo(parent);
			assertThat(result.getParentName()).isEqualTo("Parent");
			verify(repo).saveAndFlush(existing);
		}

		@Test
		void update_shouldThrowWhenCategoryIsItsOwnParent() {
			UUID id = UUID.randomUUID();
			Category existing = new Category("Old Name", "old-name", "desc");
			CategoryCreateDTO dto = new CategoryCreateDTO("Old Name", "desc", id);

			when(repo.findById(id)).thenReturn(Optional.of(existing));
			when(repo.existsByNameAndIdNot(dto.getName(), id)).thenReturn(false);
			when(repo.existsBySlugAndIdNot("old-name", id)).thenReturn(false);

			assertThatThrownBy(() -> service.update(id, dto)).isInstanceOfSatisfying(CategoryHierarchyException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_SELF_PARENT"));

			verify(repo, never()).saveAndFlush(any(Category.class));
		}

		@Test
		void update_shouldThrowWhenDirectCycleWouldBeCreated() {
			UUID currentId = UUID.randomUUID();
			UUID candidateParentId = UUID.randomUUID();

			Category current = new Category("Current", "current", "desc");
			Category candidateParent = categoryNode(currentId);
			CategoryCreateDTO dto = new CategoryCreateDTO("Current", "desc", candidateParentId);

			when(repo.findById(currentId)).thenReturn(Optional.of(current));
			when(repo.existsByNameAndIdNot(dto.getName(), currentId)).thenReturn(false);
			when(repo.existsBySlugAndIdNot("current", currentId)).thenReturn(false);
			when(repo.findById(candidateParentId)).thenReturn(Optional.of(candidateParent));

			assertThatThrownBy(() -> service.update(currentId, dto)).isInstanceOfSatisfying(
					CategoryHierarchyException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_CYCLE_DETECTED"));
			verify(repo, never()).saveAndFlush(any(Category.class));
		}

		@Test
		void update_shouldThrowWhenIndirectCycleWouldBeCreated() {
			UUID currentId = UUID.randomUUID();
			UUID candidateParentId = UUID.randomUUID();

			Category current = new Category("Current", "current", "desc");
			Category level3 = categoryNode(currentId);
			Category level2 = categoryNode(UUID.randomUUID());
			when(level2.getParent()).thenReturn(level3);
			CategoryCreateDTO dto = new CategoryCreateDTO("Current", "desc", candidateParentId);

			when(repo.findById(currentId)).thenReturn(Optional.of(current));
			when(repo.existsByNameAndIdNot(dto.getName(), currentId)).thenReturn(false);
			when(repo.existsBySlugAndIdNot("current", currentId)).thenReturn(false);
			when(repo.findById(candidateParentId)).thenReturn(Optional.of(level2));

			assertThatThrownBy(() -> service.update(currentId, dto)).isInstanceOfSatisfying(
					CategoryHierarchyException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_CYCLE_DETECTED"));
			verify(repo, never()).saveAndFlush(any(Category.class));
		}

		@Test
		void update_shouldMapDataIntegrityViolationContainingSlugToCategorySlugAlreadyExistsException() {
			UUID id = UUID.randomUUID();
			Category existing = new Category("Old Name", "old-name", "desc");
			CategoryCreateDTO dto = new CategoryCreateDTO("Gaming Laptops", "desc", null);

			when(repo.findById(id)).thenReturn(Optional.of(existing));
			when(repo.existsByNameAndIdNot(dto.getName(), id)).thenReturn(false);
			when(repo.existsBySlugAndIdNot("gaming-laptops", id)).thenReturn(false);
			when(repo.saveAndFlush(existing)).thenThrow(new DataIntegrityViolationException("duplicate key",
					new RuntimeException("slug unique violation")));

			assertThatThrownBy(() -> service.update(id, dto))
					.isInstanceOfSatisfying(CategorySlugAlreadyExistsException.class, ex -> {
						assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_SLUG_ALREADY_EXISTS");
						assertThat(ex.getMessage()).contains("gaming-laptops");
					});
		}

		@Test
		void update_shouldMapGenericDataIntegrityViolationToCategoryAlreadyExistsException() {
			UUID id = UUID.randomUUID();
			Category existing = new Category("Old Name", "old-name", "desc");
			CategoryCreateDTO dto = new CategoryCreateDTO("Gaming Laptops", "desc", null);

			when(repo.findById(id)).thenReturn(Optional.of(existing));
			when(repo.existsByNameAndIdNot(dto.getName(), id)).thenReturn(false);
			when(repo.existsBySlugAndIdNot("gaming-laptops", id)).thenReturn(false);
			when(repo.saveAndFlush(existing)).thenThrow(new DataIntegrityViolationException("duplicate key",
					new RuntimeException("name unique violation")));

			assertThatThrownBy(() -> service.update(id, dto))
					.isInstanceOfSatisfying(CategoryAlreadyExistsException.class, ex -> {
						assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_ALREADY_EXISTS");
						assertThat(ex.getMessage()).contains("Gaming Laptops");
					});
		}

		@Test
		void update_shouldMapDataIntegrityViolationWithNonSpecificCauseToCategoryAlreadyExistsException() {
			UUID id = UUID.randomUUID();
			Category existing = new Category("Old Name", "old-name", "desc");
			CategoryCreateDTO dto = new CategoryCreateDTO("Gaming Laptops", "desc", null);

			when(repo.findById(id)).thenReturn(Optional.of(existing));
			when(repo.existsByNameAndIdNot(dto.getName(), id)).thenReturn(false);
			when(repo.existsBySlugAndIdNot("gaming-laptops", id)).thenReturn(false);
			when(repo.saveAndFlush(existing)).thenThrow(new DataIntegrityViolationException("duplicate key"));

			assertThatThrownBy(() -> service.update(id, dto)).isInstanceOfSatisfying(
					CategoryAlreadyExistsException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_ALREADY_EXISTS"));
		}
	}

	private Category categoryNode(UUID id) {
		Category node = mock(Category.class);
		when(node.getId()).thenReturn(id);
		return node;
	}
}
