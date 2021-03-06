/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.broadleafcommerce.core.catalog.domain;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.broadleafcommerce.core.catalog.service.dynamic.DefaultDynamicSkuPricingInvocationHandler;
import org.broadleafcommerce.core.catalog.service.dynamic.DynamicSkuPrices;
import org.broadleafcommerce.core.catalog.service.dynamic.SkuPricingConsiderationContext;
import org.broadleafcommerce.core.media.domain.Media;
import org.broadleafcommerce.core.media.domain.MediaImpl;
import org.broadleafcommerce.money.Money;
import org.broadleafcommerce.openadmin.client.dto.VisibilityEnum;
import org.broadleafcommerce.openadmin.client.presentation.SupportedFieldType;
import org.broadleafcommerce.presentation.AdminPresentation;
import org.broadleafcommerce.profile.util.DateUtil;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.compass.annotations.Searchable;
import org.compass.annotations.SearchableId;
import org.compass.annotations.SearchableProperty;
import org.hibernate.annotations.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.MapKey;
import org.hibernate.annotations.Parameter;

import javax.persistence.CascadeType;
import javax.persistence.*;
import javax.persistence.Entity;
import javax.persistence.Table;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.*;

/**
 * The Class SkuImpl is the default implementation of {@link Sku}. A SKU is a
 * specific item that can be sold including any specific attributes of the item
 * such as color or size. <br>
 * <br>
 * If you want to add fields specific to your implementation of
 * BroadLeafCommerce you should extend this class and add your fields. If you
 * need to make significant changes to the SkuImpl then you should implement
 * your own version of {@link Sku}. <br>
 * <br>
 * This implementation uses a Hibernate implementation of JPA configured through
 * annotations. The Entity references the following tables: BLC_SKU,
 * BLC_SKU_IMAGE
 * @see {@link Sku}
 * @author btaylor
 */
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@Table(name="BLC_SKU")
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE, region="blStandardElements")
@Searchable
public class SkuImpl implements Sku {
	
	private static final Log LOG = LogFactory.getLog(SkuImpl.class);
    /** The Constant serialVersionUID. */
    private static final long serialVersionUID = 1L;

    /** The id. */
    @Id
    @GeneratedValue(generator= "SkuId")
    @GenericGenerator(
        name="SkuId",
        strategy="org.broadleafcommerce.persistence.IdOverrideTableGenerator",
        parameters = {
            @Parameter(name="table_name", value="SEQUENCE_GENERATOR"),
            @Parameter(name="segment_column_name", value="ID_NAME"),
            @Parameter(name="value_column_name", value="ID_VAL"),
            @Parameter(name="segment_value", value="SkuImpl"),
            @Parameter(name="increment_size", value="50"),
            @Parameter(name="entity_name", value="org.broadleafcommerce.core.catalog.domain.SkuImpl")
        }
    )
    @Column(name = "SKU_ID")
    @SearchableId
    @AdminPresentation(friendlyName="Sku ID", group="Primary Key", visibility = VisibilityEnum.HIDDEN_ALL)
    protected Long id;

    /** The sale price. */
    @Column(name = "SALE_PRICE", precision=19, scale=5)
    @AdminPresentation(friendlyName="Sku Sale Price", order=9, group="Price", prominent=true, fieldType=SupportedFieldType.MONEY, groupOrder=3)
    protected BigDecimal salePrice;

    /** The retail price. */
    @Column(name = "RETAIL_PRICE", nullable=false, precision=19, scale=5)
    @AdminPresentation(friendlyName="Sku Retail Price", order=10, group="Price", prominent=true, fieldType=SupportedFieldType.MONEY, groupOrder=3)
    protected BigDecimal retailPrice;

    /** The name. */
    @Column(name = "NAME", nullable=false)
    @SearchableProperty
    @Index(name="SKU_NAME_INDEX", columnNames={"NAME"})
    @AdminPresentation(friendlyName="Sku Name", order=1, group="Sku Description", prominent=true, columnWidth="25%", groupOrder=4)
    protected String name;

    /** The description. */
    @Column(name = "DESCRIPTION")
    @AdminPresentation(friendlyName="Sku Description", order=2, group="Sku Description", largeEntry=true, groupOrder=4)
    protected String description;

    /** The long description. */
    @Lob
    @Column(name = "LONG_DESCRIPTION")
    @AdminPresentation(friendlyName="Sku Large Description", order=3, group="Sku Description", largeEntry=true, groupOrder=4)
    protected String longDescription;

    /** The taxable. */
    @Column(name = "TAXABLE_FLAG")
    @Index(name="SKU_TAXABLE_INDEX", columnNames={"TAXABLE_FLAG"})
    @AdminPresentation(friendlyName="Sku Taxable", order=4, group="Sku Description", groupOrder=4)
    protected Character taxable;

    /** The discountable. */
    @Column(name = "DISCOUNTABLE_FLAG")
    @Index(name="SKU_DISCOUNTABLE_INDEX", columnNames={"DISCOUNTABLE_FLAG"})
    @AdminPresentation(friendlyName="Sku Discountable", order=5, group="Sku Description", groupOrder=4)
    protected Character discountable;

    /** The available. */
    @Column(name = "AVAILABLE_FLAG")
    @Index(name="SKU_AVAILABLE_INDEX", columnNames={"AVAILABLE_FLAG"})
    @AdminPresentation(friendlyName="Sku Available", order=6, group="Sku Description", groupOrder=4)
    protected Character available;

    /** The active start date. */
    @Column(name = "ACTIVE_START_DATE")
    @AdminPresentation(friendlyName="Sku Start Date", order=7, group="Sku Description", groupOrder=4)
    protected Date activeStartDate;

    /** The active end date. */
    @Column(name = "ACTIVE_END_DATE")
    @Index(name="SKU_ACTIVE_INDEX", columnNames={"ACTIVE_START_DATE","ACTIVE_END_DATE"})
    @AdminPresentation(friendlyName="Sku End Date", order=8, group="Sku Description", groupOrder=4)
    protected Date activeEndDate;
    
    @Transient
    protected DynamicSkuPrices dynamicPrices = null;
	
    /** The sku images. */
    @CollectionOfElements
    @JoinTable(name = "BLC_SKU_IMAGE", joinColumns = @JoinColumn(name = "SKU_ID"))
    @org.hibernate.annotations.MapKey(columns = { @Column(name = "NAME", length = 5, nullable = false) })
    @Column(name = "URL")
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE, region="blStandardElements")
    @Deprecated
    protected Map<String, String> skuImages = new HashMap<String, String>();

    /** The sku media. */
    @ManyToMany(targetEntity = MediaImpl.class)
    @JoinTable(name = "BLC_SKU_MEDIA_MAP", inverseJoinColumns = @JoinColumn(name = "MEDIA_ID", referencedColumnName = "MEDIA_ID"))
    @MapKey(columns = {@Column(name = "MAP_KEY", nullable = false)})
    @Cascade(value={org.hibernate.annotations.CascadeType.ALL, org.hibernate.annotations.CascadeType.DELETE_ORPHAN})
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE, region="blStandardElements")
    protected Map<String, Media> skuMedia = new HashMap<String , Media>();

    @ManyToMany(fetch = FetchType.LAZY, targetEntity = ProductImpl.class)
    @JoinTable(name = "BLC_PRODUCT_SKU_XREF", joinColumns = @JoinColumn(name = "SKU_ID", referencedColumnName = "SKU_ID", nullable = true), inverseJoinColumns = @JoinColumn(name = "PRODUCT_ID", referencedColumnName = "PRODUCT_ID", nullable = true))
    protected List<Product> allParentProducts = new ArrayList<Product>();
    
    @OneToMany(mappedBy = "sku", targetEntity = SkuAttributeImpl.class, cascade = {CascadeType.ALL})
    @Cascade(value={org.hibernate.annotations.CascadeType.ALL, org.hibernate.annotations.CascadeType.DELETE_ORPHAN})    
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE, region="blStandardElements")
    @BatchSize(size = 50)
    protected List<SkuAttribute> skuAttributes  = new ArrayList<SkuAttribute>();

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#getId()
     */
    public Long getId() {
        return id;
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#setId(java.lang.Long)
     */
    public void setId(Long id) {
        this.id = id;
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#getSalePrice()
     */
    public Money getSalePrice() {
    	if (dynamicPrices != null) {
    		return dynamicPrices.getSalePrice();
    	}
    	if (
    			SkuPricingConsiderationContext.getSkuPricingConsiderationContext() != null && 
    			SkuPricingConsiderationContext.getSkuPricingConsiderationContext().size() > 0 &&
    			SkuPricingConsiderationContext.getSkuPricingService() != null
    	) {
    		DefaultDynamicSkuPricingInvocationHandler handler = new DefaultDynamicSkuPricingInvocationHandler(this);
    		Sku proxy = (Sku) Proxy.newProxyInstance(getClass().getClassLoader(), getClass().getInterfaces(), handler);
    		dynamicPrices = SkuPricingConsiderationContext.getSkuPricingService().getSkuPrices(proxy, SkuPricingConsiderationContext.getSkuPricingConsiderationContext());
    		handler.reset();
    		return dynamicPrices.getSalePrice();
    	}
        return salePrice == null ? null : new Money(salePrice);
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#setSalePrice(org.broadleafcommerce.util.money.Money)
     */
    public void setSalePrice(Money salePrice) {
        this.salePrice = Money.toAmount(salePrice);
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#getRetailPrice()
     */
    public Money getRetailPrice() {
    	if (dynamicPrices != null) {
    		return dynamicPrices.getRetailPrice();
    	}
    	if (
    			SkuPricingConsiderationContext.getSkuPricingConsiderationContext() != null && 
    			SkuPricingConsiderationContext.getSkuPricingConsiderationContext().size() > 0 &&
    			SkuPricingConsiderationContext.getSkuPricingService() != null
    	) {
    		DefaultDynamicSkuPricingInvocationHandler handler = new DefaultDynamicSkuPricingInvocationHandler(this);
    		Sku proxy = (Sku) Proxy.newProxyInstance(getClass().getClassLoader(), getClass().getInterfaces(), handler);
    		dynamicPrices = SkuPricingConsiderationContext.getSkuPricingService().getSkuPrices(proxy, SkuPricingConsiderationContext.getSkuPricingConsiderationContext());
    		handler.reset();
    		return dynamicPrices.getRetailPrice();
    	}
        return retailPrice == null ? null : new Money(retailPrice);
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#setRetailPrice(org.broadleafcommerce
     * .util.money.Money)
     */
    public void setRetailPrice(Money retailPrice) {
        this.retailPrice = Money.toAmount(retailPrice);
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#getListPrice()
     */
    public Money getListPrice() {
        return new Money(retailPrice);
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#setListPrice(org.broadleafcommerce
     * .util.money.Money)
     */
    public void setListPrice(Money listPrice) {
        this.retailPrice = Money.toAmount(listPrice);
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#getName()
     */
    public String getName() {
        return name;
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#setName(java.lang.String)
     */
    public void setName(String name) {
        this.name = name;
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#getDescription()
     */
    public String getDescription() {
        return description;
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#setDescription(java.lang.String)
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#getLongDescription()
     */
    public String getLongDescription() {
        return longDescription;
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#setLongDescription(java.lang
     * .String)
     */
    public void setLongDescription(String longDescription) {
        this.longDescription = longDescription;
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#isTaxable()
     */
    public Boolean isTaxable() {
        if (taxable == null)
            return null;
        return taxable == 'Y' ? Boolean.TRUE : Boolean.FALSE;
    }

    /*
     * This is to facilitate serialization to non-Java clients
     */
    @JsonIgnore
    public Boolean getTaxable() {
        return isTaxable();
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#setTaxable(java.lang.Boolean)
     */
    public void setTaxable(Boolean taxable) {
        if (taxable == null) {
            this.taxable = null;
        } else {
            this.taxable = taxable ? 'Y' : 'N';
        }
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#isDiscountable()
     */
    public Boolean isDiscountable() {
        if (discountable == null)
            return null;
        return discountable == 'Y' ? Boolean.TRUE : Boolean.FALSE;
    }

    /*
     * This is to facilitate serialization to non-Java clients
     */
    @JsonIgnore
    public Boolean getDiscountable() {
        return isDiscountable();
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#setDiscountable(java.lang.Boolean)
     */
    public void setDiscountable(Boolean discountable) {
        if (discountable == null) {
            this.discountable = null;
        } else {
            this.discountable = discountable ? 'Y' : 'N';
        }
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#isAvailable()
     */
    public Boolean isAvailable() {
        if (available == null)
            return null;
        return available == 'Y' ? Boolean.TRUE : Boolean.FALSE;
    }

    @JsonIgnore
    public Boolean getAvailable() {
    	return isAvailable();
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#setAvailable(java.lang.Boolean)
     */
    public void setAvailable(Boolean available) {
        if (available == null) {
            this.available = null;
        } else {
            this.available = available ? 'Y' : 'N';
        }
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#getActiveStartDate()
     */
    public Date getActiveStartDate() {
        return activeStartDate;
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#setActiveStartDate(java.util
     * .Date)
     */
    public void setActiveStartDate(Date activeStartDate) {
        this.activeStartDate = activeStartDate;
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#getActiveEndDate()
     */
    public Date getActiveEndDate() {
        return activeEndDate;
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#setActiveEndDate(java.util.Date)
     */
    public void setActiveEndDate(Date activeEndDate) {
        this.activeEndDate = activeEndDate;
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#isActive()
     */
    public boolean isActive() {
        if (LOG.isDebugEnabled()) {
            if (!DateUtil.isActive(getActiveStartDate(), getActiveEndDate(), true)) {
                LOG.debug("sku, " + id + ", inactive due to date");
            }
        }
        return DateUtil.isActive(getActiveStartDate(), getActiveEndDate(), true);
    }

    public boolean isActive(Product product, Category category) {
        if (LOG.isDebugEnabled()) {
            if (!DateUtil.isActive(getActiveStartDate(), getActiveEndDate(), true)) {
                LOG.debug("sku, " + id + ", inactive due to date");
            } else if (!product.isActive()) {
                LOG.debug("sku, " + id + ", inactive due to product being inactive");
            } else if (!category.isActive()) {
                LOG.debug("sku, " + id + ", inactive due to category being inactive");
            }
        }
        return this.isActive() && product.isActive() && category.isActive();
    }
    
    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#getSkuImages()
     */
    @Deprecated
    public Map<String, String> getSkuImages() {
        return skuImages;
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#getSkuImage(java.lang.String)
     */
    @Deprecated
    public String getSkuImage(String imageKey) {
        return skuImages.get(imageKey);
    }

    /*
     * (non-Javadoc)
     * @see org.broadleafcommerce.core.catalog.domain.Sku#setSkuImages(java.util.Map)
     */
    @Deprecated
    public void setSkuImages(Map<String, String> skuImages) {
        this.skuImages = skuImages;
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#getSkuMedia()
     */
    public Map<String, Media> getSkuMedia() {
        return skuMedia;
    }

    /*
     * (non-Javadoc)
     * @see
     * org.broadleafcommerce.core.catalog.domain.Sku#getSkuImage(java.util.Map)
     */
    public void setSkuMedia(Map<String, Media> skuMedia) {
        this.skuMedia = skuMedia;
    }

    public List<Product> getAllParentProducts() {
        return allParentProducts;
    }

    public void setAllParentProducts(List<Product> allParentProducts) {
        this.allParentProducts = allParentProducts;
    }

    /**
	 * @return the skuAttributes
	 */
	public List<SkuAttribute> getSkuAttributes() {
		return skuAttributes;
	}

	/**
	 * @param skuAttributes the skuAttributes to set
	 */
	public void setSkuAttributes(List<SkuAttribute> skuAttributes) {
		this.skuAttributes = skuAttributes;
	}

	@Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        SkuImpl other = (SkuImpl) obj;

        if (id != null && other.id != null) {
            return id.equals(other.id);
        }

        if (name == null) {
            if (other.name != null)
                return false;
        } else if (!name.equals(other.name))
            return false;
        return true;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        return result;
    }
}
