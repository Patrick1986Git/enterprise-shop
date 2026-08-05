package com.company.shop.module.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.company.shop.module.category.entity.Category;
import com.company.shop.module.category.repository.CategoryRepository;
import com.company.shop.module.product.dto.ProductCreateDTO;
import com.company.shop.module.product.dto.ProductResponseDTO;
import com.company.shop.module.product.entity.Product;
import com.company.shop.module.product.exception.ProductCategoryNotFoundException;
import com.company.shop.module.product.exception.ProductNotFoundException;
import com.company.shop.module.product.exception.ProductSkuAlreadyExistsException;
import com.company.shop.module.product.exception.ProductSlugAlreadyExistsException;
import com.company.shop.module.product.mapper.ProductMapper;
import com.company.shop.module.product.repository.ProductRepository;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductMapper productMapper;

    private ProductServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductServiceImpl(productRepository, categoryRepository, productMapper);
    }

    @Test
    void findById_shouldReturnMappedDtoWhenProductExists() {
        UUID productId = UUID.randomUUID();
        Product product = product();
        ProductResponseDTO response = stubResponse(productId, product.getSlug(), product.getSku());
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(productMapper.toDto(product)).thenReturn(response);

        ProductResponseDTO result = service.findById(productId);

        assertThat(result).isSameAs(response);
        verify(productRepository).findById(productId);
        verify(productMapper).toDto(product);
    }

    @Test
    void findById_shouldThrowWhenProductDoesNotExist() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(productId))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining(productId.toString());

        verifyNoInteractions(productMapper);
    }

    @Test
    void findBySlug_shouldReturnMappedDtoWhenProductExists() {
        Product product = product();
        ProductResponseDTO response = stubResponse(UUID.randomUUID(), product.getSlug(), product.getSku());
        when(productRepository.findBySlug(product.getSlug())).thenReturn(Optional.of(product));
        when(productMapper.toDto(product)).thenReturn(response);

        ProductResponseDTO result = service.findBySlug(product.getSlug());

        assertThat(result).isSameAs(response);
        verify(productRepository).findBySlug(product.getSlug());
        verify(productMapper).toDto(product);
    }

    @Test
    void findBySlug_shouldThrowWhenProductDoesNotExist() {
        String slug = "missing-product";
        when(productRepository.findBySlug(slug)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findBySlug(slug))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining(slug);

        verifyNoInteractions(productMapper);
    }

    @Test
    void create_shouldThrowWhenSkuAlreadyExists() {
        ProductCreateDTO dto = dto("Test Product", "SKU-123", UUID.randomUUID());
        when(productRepository.existsBySku(dto.getSku())).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(ProductSkuAlreadyExistsException.class)
                .hasMessageContaining(dto.getSku());

        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void create_shouldThrowWhenCategoryNotFound() {
        ProductCreateDTO dto = dto("Test Product", "SKU-123", UUID.randomUUID());
        when(productRepository.existsBySku(dto.getSku())).thenReturn(false);
        when(categoryRepository.findById(dto.getCategoryId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(ProductCategoryNotFoundException.class)
                .hasMessageContaining(dto.getCategoryId().toString());

        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void create_shouldGenerateDeterministicSlugSuffixWhenBaseSlugExists() {
        UUID categoryId = UUID.randomUUID();
        ProductCreateDTO dto = dto("Phone Case", "SKU-123", categoryId);
        Category category = category();

        when(productRepository.existsBySku(dto.getSku())).thenReturn(false);
        when(categoryRepository.findById(dto.getCategoryId())).thenReturn(Optional.of(category));
        when(productRepository.existsBySlug("phone-case")).thenReturn(true);
        when(productRepository.existsBySlug("phone-case-2")).thenReturn(false);
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productMapper.toDto(any(Product.class))).thenReturn(stubResponse());

        service.create(dto);

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).saveAndFlush(productCaptor.capture());
        assertThat(productCaptor.getValue().getSlug()).isEqualTo("phone-case-2");
    }

    @Test
    void create_shouldSelectFirstAvailableDeterministicSlugCandidateBeyondFirstSuffix() {
        UUID categoryId = UUID.randomUUID();
        ProductCreateDTO dto = dto("Phone Case", "SKU-123", categoryId);
        when(productRepository.existsBySku(dto.getSku())).thenReturn(false);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category()));
        when(productRepository.existsBySlug("phone-case")).thenReturn(true);
        when(productRepository.existsBySlug("phone-case-2")).thenReturn(true);
        when(productRepository.existsBySlug("phone-case-3")).thenReturn(true);
        when(productRepository.existsBySlug("phone-case-4")).thenReturn(false);
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productMapper.toDto(any(Product.class))).thenReturn(stubResponse());

        service.create(dto);

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).saveAndFlush(productCaptor.capture());
        assertThat(productCaptor.getValue().getSlug()).isEqualTo("phone-case-4");
    }

    @Test
    void create_shouldUseRandomizedCandidateWhenDeterministicSlugCandidatesAreTaken() {
        UUID categoryId = UUID.randomUUID();
        ProductCreateDTO dto = dto("Phone Case", "SKU-123", categoryId);
        when(productRepository.existsBySku(dto.getSku())).thenReturn(false);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category()));
        when(productRepository.existsBySlug(any(String.class))).thenAnswer(invocation -> {
            String slug = invocation.getArgument(0);
            return !slug.matches("phone-case-[a-f0-9]{8}");
        });
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productMapper.toDto(any(Product.class))).thenReturn(stubResponse());

        service.create(dto);

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).saveAndFlush(productCaptor.capture());
        assertThat(productCaptor.getValue().getSlug()).matches("^phone-case-[a-f0-9]{8}$");
    }

    @Test
    void create_shouldThrowWhenAllSlugCandidatesAreTaken() {
        ProductCreateDTO dto = dto("Phone Case", "SKU-123", UUID.randomUUID());
        when(productRepository.existsBySku(dto.getSku())).thenReturn(false);
        when(categoryRepository.findById(dto.getCategoryId())).thenReturn(Optional.of(category()));
        when(productRepository.existsBySlug(any(String.class))).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(ProductSlugAlreadyExistsException.class)
                .hasMessageContaining("phone-case");

        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void create_shouldUseRandomizedFallbackWhenSlugBecomesBlank() {
        UUID categoryId = UUID.randomUUID();
        ProductCreateDTO dto = dto("___", "SKU-XYZ", categoryId);
        Category category = category();

        when(productRepository.existsBySku(dto.getSku())).thenReturn(false);
        when(categoryRepository.findById(dto.getCategoryId())).thenReturn(Optional.of(category));
        when(productRepository.existsBySlug(any(String.class))).thenReturn(false);
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productMapper.toDto(any(Product.class))).thenReturn(stubResponse());

        service.create(dto);

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).saveAndFlush(productCaptor.capture());

        String slug = productCaptor.getValue().getSlug();
        assertThat(slug).matches("^product-[a-f0-9]{8}$");
    }

    @Test
    void update_shouldThrowWhenProductDoesNotExist() {
        UUID productId = UUID.randomUUID();
        ProductCreateDTO dto = dto("Test Product", "SKU-123", UUID.randomUUID());
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(productId, dto))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining(productId.toString());

        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void update_shouldThrowWhenSkuBelongsToAnotherProduct() {
        UUID productId = UUID.randomUUID();
        ProductCreateDTO dto = dto("Test Product", "NEW-SKU", UUID.randomUUID());
        when(productRepository.findById(productId)).thenReturn(Optional.of(product()));
        when(productRepository.existsBySkuAndIdNot(dto.getSku(), productId)).thenReturn(true);

        assertThatThrownBy(() -> service.update(productId, dto))
                .isInstanceOf(ProductSkuAlreadyExistsException.class)
                .hasMessageContaining(dto.getSku());

        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void update_shouldThrowWhenCategoryDoesNotExist() {
        UUID productId = UUID.randomUUID();
        ProductCreateDTO dto = dto("Test Product", "NEW-SKU", UUID.randomUUID());
        when(productRepository.findById(productId)).thenReturn(Optional.of(product()));
        when(productRepository.existsBySkuAndIdNot(dto.getSku(), productId)).thenReturn(false);
        when(categoryRepository.findById(dto.getCategoryId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(productId, dto))
                .isInstanceOf(ProductCategoryNotFoundException.class)
                .hasMessageContaining(dto.getCategoryId().toString());

        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void update_shouldUseExcludedProductSlugLookupWhenValidatingSlug() {
        UUID productId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        ProductCreateDTO dto = dto("Test Product", "NEW-SKU", categoryId);
        Product existing = product();
        when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
        when(productRepository.existsBySkuAndIdNot(dto.getSku(), productId)).thenReturn(false);
        when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category()));
        when(productRepository.existsBySlugAndIdNot("test-product", productId)).thenReturn(false);
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productMapper.toDto(any(Product.class))).thenReturn(stubResponse());

        service.update(productId, dto);

        verify(productRepository).existsBySlugAndIdNot("test-product", productId);
        verify(productRepository, never()).existsBySlug("test-product");
        assertThat(existing.getSku()).isEqualTo("NEW-SKU");
    }

    @Test
    void update_shouldTranslateNestedSkuConstraintViolation() {
        UUID productId = UUID.randomUUID();
        ProductCreateDTO dto = dto("Test Product", "NEW-SKU", UUID.randomUUID());
        prepareUpdateForSaveFailure(productId, dto, product());
        DataIntegrityViolationException failure = dataIntegrityViolation("uq_products_sku");
        when(productRepository.saveAndFlush(any(Product.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.update(productId, dto))
                .isInstanceOf(ProductSkuAlreadyExistsException.class)
                .hasMessageContaining(dto.getSku());
    }

    @Test
    void create_shouldTranslateNestedSlugConstraintViolation() {
        ProductCreateDTO dto = dto("Test Product", "SKU-123", UUID.randomUUID());
        prepareCreateForSaveFailure(dto);
        DataIntegrityViolationException failure = dataIntegrityViolation("uq_products_slug");
        when(productRepository.saveAndFlush(any(Product.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(ProductSlugAlreadyExistsException.class)
                .hasMessageContaining("test-product");
    }

    @Test
    void create_shouldFallbackToSkuRepositoryEvidenceWhenConstraintIsUnknown() {
        ProductCreateDTO dto = dto("Test Product", "SKU-123", UUID.randomUUID());
        prepareCreateForSaveFailure(dto);
        DataIntegrityViolationException failure = dataIntegrityViolation("unknown_constraint");
        when(productRepository.saveAndFlush(any(Product.class))).thenThrow(failure);
        when(productRepository.existsBySku(dto.getSku())).thenReturn(false, true);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(ProductSkuAlreadyExistsException.class)
                .hasMessageContaining(dto.getSku());
    }

    @Test
    void create_shouldFallbackToSlugRepositoryEvidenceWhenConstraintIsUnknown() {
        ProductCreateDTO dto = dto("Test Product", "SKU-123", UUID.randomUUID());
        prepareCreateForSaveFailure(dto);
        DataIntegrityViolationException failure = dataIntegrityViolation("unknown_constraint");
        when(productRepository.saveAndFlush(any(Product.class))).thenThrow(failure);
        when(productRepository.existsBySku(dto.getSku())).thenReturn(false, false);
        when(productRepository.existsBySlug("test-product")).thenReturn(false, true);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(ProductSlugAlreadyExistsException.class)
                .hasMessageContaining("test-product");
    }

    @Test
    void update_shouldFallbackToSkuRepositoryEvidenceWhenConstraintIsUnknown() {
        UUID productId = UUID.randomUUID();
        ProductCreateDTO dto = dto("Test Product", "NEW-SKU", UUID.randomUUID());
        prepareUpdateForSaveFailure(productId, dto, product());
        DataIntegrityViolationException failure = dataIntegrityViolation("unknown_constraint");
        when(productRepository.saveAndFlush(any(Product.class))).thenThrow(failure);
        when(productRepository.existsBySkuAndIdNot(dto.getSku(), productId)).thenReturn(false, true);

        assertThatThrownBy(() -> service.update(productId, dto))
                .isInstanceOf(ProductSkuAlreadyExistsException.class)
                .hasMessageContaining(dto.getSku());
    }

    @Test
    void update_shouldFallbackToSlugRepositoryEvidenceWhenConstraintIsUnknown() {
        UUID productId = UUID.randomUUID();
        ProductCreateDTO dto = dto("Test Product", "NEW-SKU", UUID.randomUUID());
        prepareUpdateForSaveFailure(productId, dto, product());
        DataIntegrityViolationException failure = dataIntegrityViolation("unknown_constraint");
        when(productRepository.saveAndFlush(any(Product.class))).thenThrow(failure);
        when(productRepository.existsBySkuAndIdNot(dto.getSku(), productId)).thenReturn(false, false);
        when(productRepository.existsBySlugAndIdNot("test-product", productId)).thenReturn(false, true);

        assertThatThrownBy(() -> service.update(productId, dto))
                .isInstanceOf(ProductSlugAlreadyExistsException.class)
                .hasMessageContaining("test-product");
    }

    @Test
    void create_shouldRethrowOriginalDataIntegrityViolationWhenConstraintIsUnknownAndNoRepositoryEvidenceExists() {
        ProductCreateDTO dto = dto("Test Product", "SKU-123", UUID.randomUUID());
        prepareCreateForSaveFailure(dto);
        DataIntegrityViolationException failure = dataIntegrityViolation("unknown_constraint");
        when(productRepository.saveAndFlush(any(Product.class))).thenThrow(failure);
        when(productRepository.existsBySku(dto.getSku())).thenReturn(false, false);
        when(productRepository.existsBySlug("test-product")).thenReturn(false, false);

        assertThatThrownBy(() -> service.create(dto))
                .isSameAs(failure);
    }

    @Test
    void update_shouldApplySoftDeleteTransitionWhenProductExists() {
        UUID productId = UUID.randomUUID();
        Product product = product();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        service.delete(productId);

        assertThat(product.isDeleted()).isTrue();
    }

    @Test
    void delete_shouldThrowWhenProductDoesNotExist() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(productId))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining(productId.toString());
    }

    private void prepareCreateForSaveFailure(ProductCreateDTO dto) {
        when(productRepository.existsBySku(dto.getSku())).thenReturn(false);
        when(categoryRepository.findById(dto.getCategoryId())).thenReturn(Optional.of(category()));
        when(productRepository.existsBySlug("test-product")).thenReturn(false);
    }

    private void prepareUpdateForSaveFailure(UUID productId, ProductCreateDTO dto, Product existing) {
        when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
        when(productRepository.existsBySkuAndIdNot(dto.getSku(), productId)).thenReturn(false);
        when(categoryRepository.findById(dto.getCategoryId())).thenReturn(Optional.of(category()));
        when(productRepository.existsBySlugAndIdNot("test-product", productId)).thenReturn(false);
    }

    private DataIntegrityViolationException dataIntegrityViolation(String constraintName) {
        ConstraintViolationException constraintViolation = new ConstraintViolationException(
                "constraint violation",
                new SQLException("duplicate key"),
                constraintName);
        RuntimeException wrapper = new RuntimeException("wrapped", constraintViolation);
        return new DataIntegrityViolationException("save failed", wrapper);
    }

    private Product product() {
        return new Product("Existing Product", "existing-product", "SKU-EXISTING", "Description", BigDecimal.TEN, 2,
                category());
    }

    private Category category() {
        return new Category("Accessories", "accessories", "desc");
    }

    private ProductCreateDTO dto(String name, String sku, UUID categoryId) {
        return new ProductCreateDTO(name, sku, "Description", BigDecimal.valueOf(19.99), 10, categoryId,
                List.of("https://img.example/1.png"));
    }

    private ProductResponseDTO stubResponse() {
        return stubResponse(UUID.randomUUID(), "slug", "sku");
    }

    private ProductResponseDTO stubResponse(UUID id, String slug, String sku) {
        return new ProductResponseDTO(id, "name", slug, sku, "desc", BigDecimal.ONE,
                1, UUID.randomUUID(), "cat", 0.0, 0, List.of());
    }
}
