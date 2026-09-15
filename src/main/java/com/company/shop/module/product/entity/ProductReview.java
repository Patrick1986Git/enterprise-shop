package com.company.shop.module.product.entity;

import java.util.UUID;

import org.hibernate.annotations.SQLRestriction;

import com.company.shop.common.model.SoftDeleteEntity;
import com.company.shop.module.product.exception.ProductDataInvalidException;
import com.company.shop.module.product.exception.ProductInvariantViolationException;
import com.company.shop.module.product.exception.ProductReviewRatingInvalidException;
import com.company.shop.module.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "product_reviews")
@SQLRestriction("deleted = false")
public class ProductReview extends SoftDeleteEntity {

	private static final int MIN_RATING = 1;
	private static final int MAX_RATING = 5;
	private static final int MAX_COMMENT_LENGTH = 1000;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id")
	private Product product;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id")
	private User user;

	@Column(name = "user_id", insertable = false, updatable = false, nullable = false)
	private UUID authorId;

	@Column(name = "author_name", nullable = false, length = 201)
	private String authorName;

	@Column(nullable = false)
	private int rating;

	@Column(columnDefinition = "TEXT")
	private String comment;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected ProductReview() {
	}

	public ProductReview(Product product, User user, int rating, String comment) {
		validateRequiredAssociationsForConstruction(product, user);
		validateRating(rating);
		String sanitizedComment = sanitizeComment(comment);
		validateComment(sanitizedComment);
		this.product = product;
		this.user = user;
		this.authorName = displayName(user);
		this.rating = rating;
		this.comment = sanitizedComment;
	}

	public Product getProduct() {
		return product;
	}

	public User getUser() {
		return user;
	}

	public UUID getAuthorId() {
		return authorId != null ? authorId : user.getId();
	}

	public String getAuthorName() {
		return authorName;
	}

	public int getRating() {
		return rating;
	}

	public String getComment() {
		return comment;
	}

	public void update(int rating, String comment) {
		validateInvariantState();
		validateRating(rating);
		String sanitizedComment = sanitizeComment(comment);
		validateComment(sanitizedComment);
		this.rating = rating;
		this.comment = sanitizedComment;
	}

	private void validateRequiredAssociationsForConstruction(Product product, User user) {
		if (product == null) {
			throw new ProductDataInvalidException("Review product is required");
		}
		if (user == null) {
			throw new ProductDataInvalidException("Review user is required");
		}
	}

	private void validateInvariantState() {
		if (this.product == null) {
			throw new ProductInvariantViolationException("Review entity is in invalid state: product association is missing");
		}
		if (this.user == null) {
			throw new ProductInvariantViolationException("Review entity is in invalid state: user association is missing");
		}
	}

	private void validateRating(int rating) {
		if (rating < MIN_RATING || rating > MAX_RATING) {
			throw new ProductReviewRatingInvalidException(rating);
		}
	}

	private void validateComment(String comment) {
		if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
			throw new ProductDataInvalidException("Review comment must not exceed " + MAX_COMMENT_LENGTH + " characters");
		}
	}

	private String sanitizeComment(String comment) {
		if (comment == null) {
			return null;
		}
		String normalized = comment.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private String displayName(User user) {
		String firstName = user.getFirstName() == null ? "" : user.getFirstName().trim();
		String lastName = user.getLastName() == null ? "" : user.getLastName().trim();
		String displayName = (firstName + " " + lastName).trim();
		return displayName.isEmpty() ? "Anonymous" : displayName;
	}
}
