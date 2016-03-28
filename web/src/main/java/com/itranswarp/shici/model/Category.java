package com.itranswarp.shici.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.itranswarp.warpdb.entity.BaseEntity;

/**
 * Category that contains a list of courses.
 * 
 * @author michael
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "UK_Category_tag", columnNames = { "tag" }))
public class Category extends BaseEntity {

	@Column(length = VARCHAR_50, nullable = false, updatable = false)
	public String tag;

	@Column(nullable = false)
	public long displayOrder;

	@Column(length = VARCHAR_100, nullable = false)
	public String name;

	@Column(length = VARCHAR_1000, nullable = false)
	public String description;

	@Override
	public String toString() {
		return "{Category: tag=" + tag + ", name=" + name + "}";
	}
}
