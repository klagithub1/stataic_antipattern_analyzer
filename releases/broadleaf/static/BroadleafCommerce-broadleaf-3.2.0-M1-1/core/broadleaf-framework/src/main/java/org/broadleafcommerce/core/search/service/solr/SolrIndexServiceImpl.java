/*
 * #%L
 * BroadleafCommerce Framework
 * %%
 * Copyright (C) 2009 - 2013 Broadleaf Commerce
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.broadleafcommerce.core.search.service.solr;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.broadleafcommerce.common.exception.ExceptionHelper;
import org.broadleafcommerce.common.exception.ServiceException;
import org.broadleafcommerce.common.extension.ExtensionResultStatusType;
import org.broadleafcommerce.common.locale.domain.Locale;
import org.broadleafcommerce.common.locale.service.LocaleService;
import org.broadleafcommerce.common.util.BLCCollectionUtils;
import org.broadleafcommerce.common.util.StopWatch;
import org.broadleafcommerce.common.util.TransactionUtils;
import org.broadleafcommerce.common.util.TypedTransformer;
import org.broadleafcommerce.common.web.BroadleafRequestContext;
import org.broadleafcommerce.core.catalog.dao.ProductDao;
import org.broadleafcommerce.core.catalog.dao.SkuDao;
import org.broadleafcommerce.core.catalog.domain.Product;
import org.broadleafcommerce.core.catalog.domain.ProductBundle;
import org.broadleafcommerce.core.catalog.domain.Sku;
import org.broadleafcommerce.core.catalog.service.dynamic.DynamicSkuActiveDatesService;
import org.broadleafcommerce.core.catalog.service.dynamic.DynamicSkuPricingService;
import org.broadleafcommerce.core.catalog.service.dynamic.SkuActiveDateConsiderationContext;
import org.broadleafcommerce.core.catalog.service.dynamic.SkuPricingConsiderationContext;
import org.broadleafcommerce.core.search.dao.CatalogStructure;
import org.broadleafcommerce.core.search.dao.FieldDao;
import org.broadleafcommerce.core.search.dao.SolrIndexDao;
import org.broadleafcommerce.core.search.domain.Field;
import org.broadleafcommerce.core.search.domain.solr.FieldType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Resource;


/**
 * Responsible for building and rebuilding the Solr index
 * 
 * @author Andre Azzolini (apazzolini)
 * @author Jeff Fischer
 */
@Service("blSolrIndexService")
public class SolrIndexServiceImpl implements SolrIndexService {

    private static final Log LOG = LogFactory.getLog(SolrIndexServiceImpl.class);

    @Value("${solr.index.product.pageSize}")
    protected int pageSize;

    @Value("${solr.index.use.sku}")
    protected boolean useSku;

    @Resource(name = "blProductDao")
    protected ProductDao productDao;

    @Resource(name = "blSkuDao")
    protected SkuDao skuDao;

    @Resource(name = "blFieldDao")
    protected FieldDao fieldDao;

    @Resource(name = "blLocaleService")
    protected LocaleService localeService;

    @Resource(name = "blSolrHelperService")
    protected SolrHelperService shs;

    @Resource(name = "blSolrSearchServiceExtensionManager")
    protected SolrSearchServiceExtensionManager extensionManager;

    @Resource(name = "blTransactionManager")
    protected PlatformTransactionManager transactionManager;

    @Resource(name = "blSolrIndexDao")
    protected SolrIndexDao solrIndexDao;

    public static String PRODUCT_ATTR_MAP = "productAttributes";

    public static String SKU_ATTR_MAP = "skuAttributes";

    @Override
    public void performCachedOperation(SolrIndexCachedOperation.CacheOperation cacheOperation) throws ServiceException {
        try {
            CatalogStructure cache = new CatalogStructure();
            SolrIndexCachedOperation.setCache(cache);
            cacheOperation.execute();
        } finally {
            if (LOG.isInfoEnabled()) {
                LOG.info("Cleaning up Solr index cache from memory - size approx: " + getCacheSizeInMemoryApproximation(SolrIndexCachedOperation.getCache()) + " bytes");
            }
            SolrIndexCachedOperation.clearCache();
        }
    }

    protected int getCacheSizeInMemoryApproximation(CatalogStructure structure) {
        try {
            if (structure == null) {
                return 0;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(structure);
            IOUtils.closeQuietly(oos);
            int size = baos.size();
            IOUtils.closeQuietly(baos);
            return size;
        } catch (IOException e) {
            throw ExceptionHelper.refineException(e);
        }
    }

    @Override
    public void rebuildIndex() throws ServiceException, IOException {
        LOG.info("Rebuilding the solr index...");
        StopWatch s = new StopWatch();

        // If we are in single core mode, we have to delete the documents before reindexing
        if (SolrContext.isSingleCoreMode()) {
            SolrIndexServiceImpl.this.deleteAllDocuments();
        }

        Object[] pack = saveState();
        try {
            final Long numItemsToIndex;
            if (useSku) {
                numItemsToIndex = skuDao.readCountAllActiveSkus();
            } else {
                numItemsToIndex = productDao.readCountAllActiveProducts();
            }
            
            if (LOG.isDebugEnabled()) {
                LOG.debug("There are at most " + numItemsToIndex + " items to index");
            }
            
            performCachedOperation(new SolrIndexCachedOperation.CacheOperation() {
                @Override
                public void execute() throws ServiceException {
                    int page = 0;
                    while ((page * pageSize) < numItemsToIndex) {
                        buildIncrementalIndex(page, pageSize);
                        page++;
                    }
                }
            });
            
            optimizeIndex(SolrContext.getReindexServer());
        } finally {
            restoreState(pack);
        }

        // Swap the active and the reindex cores
        shs.swapActiveCores();

        // If we are not in single core mode, we delete the documents for the unused core after swapping
        if (!SolrContext.isSingleCoreMode()) {
            deleteAllDocuments();
        }

        LOG.info(String.format("Finished building index in %s", s.toLapString()));
    }

    protected void deleteAllDocuments() throws ServiceException {
        try {
            String deleteQuery = shs.getNamespaceFieldName() + ":(\"" + shs.getCurrentNamespace() + "\")";
            LOG.debug("Deleting by query: " + deleteQuery);
            SolrContext.getReindexServer().deleteByQuery(deleteQuery);
            SolrContext.getReindexServer().commit();
        } catch (Exception e) {
            throw new ServiceException("Could not delete documents", e);
        }
    }

    protected void buildIncrementalIndex(int page, int pageSize) throws ServiceException {
        buildIncrementalIndex(page, pageSize, true);
    }

    @Override
    public void buildIncrementalIndex(int page, int pageSize, boolean useReindexServer) throws ServiceException {
        TransactionStatus status = TransactionUtils.createTransaction("readItemsToIndex", TransactionDefinition.PROPAGATION_REQUIRED, transactionManager, true);
        if (SolrIndexCachedOperation.getCache() == null) {
            LOG.warn("Consider using SolrIndexService.performCachedOperation() in combination with " +
                    "SolrIndexService.buildIncrementalIndex() for better caching performance during solr indexing");
        }
        
        StopWatch s = new StopWatch();
        boolean cacheOperationManaged = false;
        try {
            Collection<SolrInputDocument> documents = new ArrayList<SolrInputDocument>();
            List<Locale> locales = getAllLocales();
            
            CatalogStructure cache = SolrIndexCachedOperation.getCache();
            if (cache != null) {
                cacheOperationManaged = true;
            } else {
                cache = new CatalogStructure();
                SolrIndexCachedOperation.setCache(cache);
            }
            
            if (useSku) {
                List<Field> fields = fieldDao.readAllSkuFields();
                List<Sku> skus = readAllActiveSkus(page, pageSize);
                List<Long> productIds = BLCCollectionUtils.collectList(skus, new TypedTransformer<Long>() {
                    @Override
                    public Long transform(Object input) {
                        return ((Sku) input).getProduct().getId();
                    }
                });
                
                solrIndexDao.populateProductCatalogStructure(productIds, SolrIndexCachedOperation.getCache());
                
                for (Sku sku : skus) {
                    SolrInputDocument doc = buildDocument(sku, fields, locales);
                    //If someone overrides the buildDocument method and determines that they don't want a product 
                    //indexed, then they can return null. If the document is null it does not get added to 
                    //to the index.
                    if (doc != null) {
                        documents.add(doc);
                    }
                }
            } else {
                List<Field> fields = fieldDao.readAllProductFields();
                List<Product> products = readAllActiveProducts(page, pageSize);
                List<Long> productIds = BLCCollectionUtils.collectList(products, new TypedTransformer<Long>() {
                    @Override
                    public Long transform(Object input) {
                        return ((Product) input).getId();
                    }
                });
                
                solrIndexDao.populateProductCatalogStructure(productIds, SolrIndexCachedOperation.getCache());
                
                for (Product product : products) {
                    SolrInputDocument doc = buildDocument(product, fields, locales);
                    //If someone overrides the buildDocument method and determines that they don't want a product 
                    //indexed, then they can return null. If the document is null it does not get added to 
                    //to the index.
                    if (doc != null) {
                        documents.add(doc);
                    }
                }
            }
            
            logDocuments(documents);

            if (!CollectionUtils.isEmpty(documents)) {
                SolrServer server = useReindexServer ? SolrContext.getReindexServer() : SolrContext.getServer();
                server.add(documents);
                server.commit();
            }
            TransactionUtils.finalizeTransaction(status, transactionManager, false);
        } catch (SolrServerException e) {
            TransactionUtils.finalizeTransaction(status, transactionManager, true);
            throw new ServiceException("Could not rebuild index", e);
        } catch (IOException e) {
            TransactionUtils.finalizeTransaction(status, transactionManager, true);
            throw new ServiceException("Could not rebuild index", e);
        } catch (RuntimeException e) {
            TransactionUtils.finalizeTransaction(status, transactionManager, true);
            throw e;
        } finally {
            if (!cacheOperationManaged) {
                SolrIndexCachedOperation.clearCache();
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Built index - page: [%s], pageSize: [%s] in [%s]", page, pageSize, s.toLapString()));
        }
    }

    /**
     * This method to read all active products will be slow if you have a large catalog. In this case, you will want to
     * read the products in a different manner. For example, if you know the fields that will be indexed, you can configure
     * a DAO object to only load those fields. You could also use a JDBC based DAO for even faster access. This default
     * implementation is only suitable for small catalogs.
     * 
     * @return the list of all active products to be used by the index building task
     */
    protected List<Product> readAllActiveProducts() {
        return productDao.readAllActiveProducts();
    }

    /**
     * This method to read active products utilizes paging to improve performance over {@link #readAllActiveProducts()}.
     * While not optimal, this will reduce the memory required to load large catalogs.
     * 
     * It could still be improved for specific implementations by only loading fields that will be indexed or by accessing
     * the database via direct JDBC (instead of Hibernate).
     * 
     * @return the list of all active products to be used by the index building task
     * @since 2.2.0
     */
    protected List<Product> readAllActiveProducts(int page, int pageSize) {
        return productDao.readAllActiveProducts(page, pageSize);
    }

    /**
     * This method to read active skus utilizes paging to improve performance.
     * While not optimal, this will reduce the memory required to load large catalogs.
     * 
     * It could still be improved for specific implementations by only loading fields that will be indexed or by accessing
     * the database via direct JDBC (instead of Hibernate).
     * 
     * @return the list of all active SKUs to be used by the index building task
     * @since 2.2.0
     */
    protected List<Sku> readAllActiveSkus(int page, int pageSize) {
        List<Sku> skus = skuDao.readAllActiveSkus(page, pageSize);
        ArrayList<Sku> skusToIndex = new ArrayList<Sku>();

        if (skus != null && !skus.isEmpty()) {
            for (Sku sku : skus) {
                //If the sku is not active, don't index it...
                if (!sku.isActive()) {
                    continue;
                }

                //If this is the default sku and the product has product options
                //and is not allowed to be sold without product options
                if (sku.getDefaultProduct() != null
                        && !sku.getProduct().getCanSellWithoutOptions()
                        && !sku.getProduct().getAdditionalSkus().isEmpty()) {
                    continue;
                }
                
                if (sku.getDefaultProduct() instanceof ProductBundle) {
                    continue;
                }

                skusToIndex.add(sku);
            }
        }

        return skusToIndex;
    }

    @Override
    public List<Locale> getAllLocales() {
        return localeService.findAllLocales();
    }
    
    @Override
    public SolrInputDocument buildDocument(final Sku sku, List<Field> fields, List<Locale> locales) {
        final SolrInputDocument document = new SolrInputDocument();
        
        attachBasicDocumentFields(sku, document);

        // Keep track of searchable fields added to the index.   We need to also add the search facets if 
        // they weren't already added as a searchable field.
        List<String> addedProperties = new ArrayList<String>();

        for (Field field : fields) {
            try {
                // Index the searchable fields
                if (field.getSearchable()) {
                    List<FieldType> searchableFieldTypes = shs.getSearchableFieldTypes(field);
                    for (FieldType sft : searchableFieldTypes) {
                        Map<String, Object> propertyValues = getPropertyValues(sku, field, sft, locales);

                        // Build out the field for every prefix
                        for (Entry<String, Object> entry : propertyValues.entrySet()) {
                            String prefix = entry.getKey();
                            prefix = StringUtils.isBlank(prefix) ? prefix : prefix + "_";

                            String solrPropertyName = shs.getPropertyNameForFieldSearchable(field, sft, prefix);
                            Object value = entry.getValue();

                            document.addField(solrPropertyName, value);
                            addedProperties.add(solrPropertyName);
                        }
                    }
                }

                // Index the faceted field type as well
                FieldType facetType = field.getFacetFieldType();
                if (facetType != null) {
                    Map<String, Object> propertyValues = getPropertyValues(sku, field, facetType, locales);

                    // Build out the field for every prefix
                    for (Entry<String, Object> entry : propertyValues.entrySet()) {
                        String prefix = entry.getKey();
                        prefix = StringUtils.isBlank(prefix) ? prefix : prefix + "_";

                        String solrFacetPropertyName = shs.getPropertyNameForFieldFacet(field, prefix);
                        Object value = entry.getValue();

                        if (!addedProperties.contains(solrFacetPropertyName)) {
                            document.addField(solrFacetPropertyName, value);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.trace("Could not get value for property[" + field.getQualifiedFieldName() + "] for sku id[" + sku.getId() + "]", e);
            }
        }
        return document;
    }

    @Override
    public SolrInputDocument buildDocument(final Product product, List<Field> fields, List<Locale> locales) {
        final SolrInputDocument document = new SolrInputDocument();

        attachBasicDocumentFields(product, document);

        // Keep track of searchable fields added to the index.   We need to also add the search facets if 
        // they weren't already added as a searchable field.
        List<String> addedProperties = new ArrayList<String>();

        for (Field field : fields) {
            try {
                // Index the searchable fields
                if (field.getSearchable()) {
                    List<FieldType> searchableFieldTypes = shs.getSearchableFieldTypes(field);
                    for (FieldType sft : searchableFieldTypes) {
                        Map<String, Object> propertyValues = getPropertyValues(product, field, sft, locales);

                        // Build out the field for every prefix
                        for (Entry<String, Object> entry : propertyValues.entrySet()) {
                            String prefix = entry.getKey();
                            prefix = StringUtils.isBlank(prefix) ? prefix : prefix + "_";

                            String solrPropertyName = shs.getPropertyNameForFieldSearchable(field, sft, prefix);
                            Object value = entry.getValue();

                            document.addField(solrPropertyName, value);
                            addedProperties.add(solrPropertyName);
                        }
                    }
                }

                // Index the faceted field type as well
                FieldType facetType = field.getFacetFieldType();
                if (facetType != null) {
                    Map<String, Object> propertyValues = getPropertyValues(product, field, facetType, locales);

                    // Build out the field for every prefix
                    for (Entry<String, Object> entry : propertyValues.entrySet()) {
                        String prefix = entry.getKey();
                        prefix = StringUtils.isBlank(prefix) ? prefix : prefix + "_";

                        String solrFacetPropertyName = shs.getPropertyNameForFieldFacet(field, prefix);
                        Object value = entry.getValue();

                        if (!addedProperties.contains(solrFacetPropertyName)) {
                            document.addField(solrFacetPropertyName, value);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.trace("Could not get value for property[" + field.getQualifiedFieldName() + "] for product id[" + product.getId() + "]", e);
            }
        }

        return document;
    }

    /**
     * Adds the ID, category, and explicitCategory fields for the product or sku to the document
     * 
     * @param product
     * @param sku
     * @param document
     */
    protected void attachBasicDocumentFields(Sku sku, SolrInputDocument document) {
        boolean cacheOperationManaged = false;
        Product product = sku.getProduct();
        try {
            CatalogStructure cache = SolrIndexCachedOperation.getCache();
            if (cache != null) {
                cacheOperationManaged = true;
            } else {
                cache = new CatalogStructure();
                SolrIndexCachedOperation.setCache(cache);
                solrIndexDao.populateProductCatalogStructure(Arrays.asList(product.getId()), SolrIndexCachedOperation.getCache());
            }
            // Add the namespace and ID fields for this product
            document.addField(shs.getNamespaceFieldName(), shs.getCurrentNamespace());
            document.addField(shs.getIdFieldName(), shs.getSolrDocumentId(document, sku));
            document.addField(shs.getSkuIdFieldName(), shs.getSkuId(sku.getId()));
            extensionManager.getProxy().attachAdditionalBasicFields(sku, document, shs);

            // The explicit categories are the ones defined by the product itself
            if (cache.getParentCategoriesByProduct().containsKey(product.getId())) {
                for (Long categoryId : cache.getParentCategoriesByProduct().get(product.getId())) {
                    document.addField(shs.getExplicitCategoryFieldName(), shs.getCategoryId(categoryId));

                    String categorySortFieldName = shs.getCategorySortFieldName(shs.getCategoryId(categoryId));
                    String displayOrderKey = categoryId + "-" + shs.getProductId(product.getId());
                    BigDecimal displayOrder = cache.getDisplayOrdersByCategoryProduct().get(displayOrderKey);
                    if (displayOrder == null) {
                        displayOrderKey = categoryId + "-" + product.getId();
                        displayOrder = cache.getDisplayOrdersByCategoryProduct().get(displayOrderKey);
                    }

                    if (document.getField(categorySortFieldName) == null) {
                        document.addField(categorySortFieldName, displayOrder);
                    }

                    // This is the entire tree of every category defined on the product
                    buildFullCategoryHierarchy(document, cache, categoryId, new HashSet<Long>());
                }
            }
        } finally {
            if (!cacheOperationManaged) {
                SolrIndexCachedOperation.clearCache();
            }
        }
    }

    protected void attachBasicDocumentFields(Product product, SolrInputDocument document) {
        boolean cacheOperationManaged = false;
        try {
            CatalogStructure cache = SolrIndexCachedOperation.getCache();
            if (cache != null) {
                cacheOperationManaged = true;
            } else {
                cache = new CatalogStructure();
                SolrIndexCachedOperation.setCache(cache);
                solrIndexDao.populateProductCatalogStructure(Arrays.asList(product.getId()), SolrIndexCachedOperation.getCache());
            }
            // Add the namespace and ID fields for this product
            document.addField(shs.getNamespaceFieldName(), shs.getCurrentNamespace());
            document.addField(shs.getIdFieldName(), shs.getSolrDocumentId(document, product));
            document.addField(shs.getProductIdFieldName(), shs.getProductId(product.getId()));
            extensionManager.getProxy().attachAdditionalBasicFields(product, document, shs);

            // The explicit categories are the ones defined by the product itself
            if (cache.getParentCategoriesByProduct().containsKey(product.getId())) {
                for (Long categoryId : cache.getParentCategoriesByProduct().get(product.getId())) {
                    document.addField(shs.getExplicitCategoryFieldName(), shs.getCategoryId(categoryId));

                    String categorySortFieldName = shs.getCategorySortFieldName(shs.getCategoryId(categoryId));
                    String displayOrderKey = categoryId + "-" + shs.getProductId(product.getId());
                    BigDecimal displayOrder = cache.getDisplayOrdersByCategoryProduct().get(displayOrderKey);
                    if (displayOrder == null) {
                        displayOrderKey = categoryId + "-" + product.getId();
                        displayOrder = cache.getDisplayOrdersByCategoryProduct().get(displayOrderKey);
                    }

                    if (document.getField(categorySortFieldName) == null) {
                        document.addField(categorySortFieldName, displayOrder);
                    }

                    // This is the entire tree of every category defined on the product
                    buildFullCategoryHierarchy(document, cache, categoryId, new HashSet<Long>());
                }
            }
        } finally {
            if (!cacheOperationManaged) {
                SolrIndexCachedOperation.clearCache();
            }
        }
    }

    /**
     * Walk the category hierarchy upwards, adding a field for each level to the solr document
     *
     * @param document the solr document for the product
     * @param cache the catalog structure cache
     * @param categoryId the current category id
     */
    protected void buildFullCategoryHierarchy(SolrInputDocument document, CatalogStructure cache, Long categoryId, Set<Long> indexedParents) {
        Long catIdToAdd = shs.getCategoryId(categoryId); 

        Collection<Object> existingValues = document.getFieldValues(shs.getCategoryFieldName());
        if (existingValues == null || !existingValues.contains(catIdToAdd)) {
            document.addField(shs.getCategoryFieldName(), catIdToAdd);
        }

        Set<Long> parents = cache.getParentCategoriesByCategory().get(categoryId);
        for (Long parent : parents) {
            if (!indexedParents.contains(parent)) {
                indexedParents.add(parent);
                buildFullCategoryHierarchy(document, cache, parent, indexedParents);
            }
        }
    }

    /**
     * Returns a map of prefix to value for the requested attributes. For example, if the requested field corresponds to
     * a Sku's description and the locales list has the en_US locale and the es_ES locale, the resulting map could be
     * 
     * { "en_US" : "A description",
     *   "es_ES" : "Una descripcion" }
     * 
     * @param sku
     * @param field
     * @param fieldType
     * @param locales
     * @return the value of the property
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     * @throws NoSuchMethodException
     */
    protected Map<String, Object> getPropertyValues(Sku sku, Field field, FieldType fieldType, List<Locale> locales) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {

        String propertyName = field.getPropertyName();
        Map<String, Object> values = new HashMap<String, Object>();

        if (extensionManager != null) {
            ExtensionResultStatusType result = extensionManager.getProxy().addPropertyValues(sku, field, fieldType, values, propertyName, locales);

            if (ExtensionResultStatusType.NOT_HANDLED.equals(result)) {
                Object propertyValue;
                if (propertyName.contains(SKU_ATTR_MAP)) {
                    propertyValue = PropertyUtils.getMappedProperty(sku, SKU_ATTR_MAP, propertyName.substring(SKU_ATTR_MAP.length() + 1));

                    // It's possible that the value is an actual object, like ProductAttribute. We'll attempt to pull the 
                    // value field out of it if it exists.
                    if (propertyValue != null) {
                        try {
                            propertyValue = PropertyUtils.getProperty(propertyValue, "value");
                        } catch (NoSuchMethodException e) {
                            // Do nothing, we'll keep the existing value
                        }
                    }
                } else {
                    propertyValue = PropertyUtils.getProperty(sku, propertyName);
                }
                values.put("", propertyValue);
            }
        }

        return values;
    }

    /**
     * Returns a map of prefix to value for the requested attributes. For example, if the requested field corresponds to
     * a Sku's description and the locales list has the en_US locale and the es_ES locale, the resulting map could be
     * 
     * { "en_US" : "A description",
     *   "es_ES" : "Una descripcion" }
     * 
     * @param product
     * @param sku
     * @param field
     * @param fieldType
     * @param locales
     * @return the value of the property
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     * @throws NoSuchMethodException
     */
    protected Map<String, Object> getPropertyValues(Product product, Field field, FieldType fieldType, List<Locale> locales) throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {

        String propertyName = field.getPropertyName();
        Map<String, Object> values = new HashMap<String, Object>();

        if (extensionManager != null) {
            ExtensionResultStatusType result = extensionManager.getProxy().addPropertyValues(product, field, fieldType, values, propertyName, locales);

            if (ExtensionResultStatusType.NOT_HANDLED.equals(result)) {
                Object propertyValue;
                if (propertyName.contains(PRODUCT_ATTR_MAP)) {
                    propertyValue = PropertyUtils.getMappedProperty(product, PRODUCT_ATTR_MAP, propertyName.substring(PRODUCT_ATTR_MAP.length() + 1));

                    // It's possible that the value is an actual object, like ProductAttribute. We'll attempt to pull the 
                    // value field out of it if it exists.
                    if (propertyValue != null) {
                        try {
                            propertyValue = PropertyUtils.getProperty(propertyValue, "value");
                        } catch (NoSuchMethodException e) {
                            // Do nothing, we'll keep the existing value
                        }
                    }
                } else {
                    propertyValue = PropertyUtils.getProperty(product, propertyName);
                }
                values.put("", propertyValue);
            }
        }

        return values;
    }

    /**
     * Converts a propertyName to one that is able to reference inside a map. For example, consider the property
     * in Product that references a List<ProductAttribute>, "productAttributes". Also consider the utility method
     * in Product called "mappedProductAttributes", which returns a map of the ProductAttributes keyed by the name
     * property in the ProductAttribute. Given the parameters "productAttributes.heatRange", "productAttributes", 
     * "mappedProductAttributes" (which would represent a property called "productAttributes.heatRange" that 
     * references a specific ProductAttribute inside of a product whose "name" property is equal to "heatRange", 
     * this method will convert this property to mappedProductAttributes(heatRange).value, which is then usable 
     * by the standard beanutils PropertyUtils class to get the value.
     * 
     * @param propertyName
     * @param listPropertyName
     * @param mapPropertyName
     * @return the converted property name
     */
    protected String convertToMappedProperty(String propertyName, String listPropertyName, String mapPropertyName) {
        String[] splitName = StringUtils.split(propertyName, ".");
        StringBuilder convertedProperty = new StringBuilder();
        for (int i = 0; i < splitName.length; i++) {
            if (convertedProperty.length() > 0) {
                convertedProperty.append(".");
            }

            if (splitName[i].equals(listPropertyName)) {
                convertedProperty.append(mapPropertyName).append("(");
                convertedProperty.append(splitName[i + 1]).append(").value");
                i++;
            } else {
                convertedProperty.append(splitName[i]);
            }
        }
        return convertedProperty.toString();
    }

    @Override
    public Object[] saveState() {
         return new Object[] {
             BroadleafRequestContext.getBroadleafRequestContext(),
             SkuPricingConsiderationContext.getSkuPricingConsiderationContext(),
             SkuPricingConsiderationContext.getSkuPricingService(),
             SkuActiveDateConsiderationContext.getSkuActiveDatesService()
         };
     }
         
    @Override
    @SuppressWarnings("rawtypes")
    public void restoreState(Object[] pack) {
         BroadleafRequestContext.setBroadleafRequestContext((BroadleafRequestContext) pack[0]);
         SkuPricingConsiderationContext.setSkuPricingConsiderationContext((HashMap) pack[1]);
         SkuPricingConsiderationContext.setSkuPricingService((DynamicSkuPricingService) pack[2]);
         SkuActiveDateConsiderationContext.setSkuActiveDatesService((DynamicSkuActiveDatesService) pack[3]);
     }
     
    @Override
    public void optimizeIndex(SolrServer server) throws ServiceException, IOException {
         try {
             if (LOG.isDebugEnabled()) {
                 LOG.debug("Optimizing the index...");
             }
             server.optimize();
         } catch (SolrServerException e) {
             throw new ServiceException("Could not optimize index", e);
         }
     }
    
    @Override
    public void logDocuments(Collection<SolrInputDocument> documents) {
        if (LOG.isTraceEnabled()) {
            for (SolrInputDocument document : documents) {
                LOG.trace(document);
            }
        }
    }
}
