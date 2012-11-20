package com.mangofactory.swagger.springmvc;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.google.common.collect.Maps;
import com.mangofactory.swagger.ControllerDocumentation;
import com.mangofactory.swagger.SwaggerConfiguration;
import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiParamImplicit;
import com.wordnik.swagger.annotations.ApiProperty;
import com.wordnik.swagger.core.Documentation;
import com.wordnik.swagger.core.DocumentationAllowableListValues;
import com.wordnik.swagger.core.DocumentationAllowableValues;
import com.wordnik.swagger.core.DocumentationEndPoint;
import com.wordnik.swagger.core.DocumentationOperation;
import com.wordnik.swagger.core.DocumentationSchema;

@Slf4j
public class MvcApiReader {

	private final WebApplicationContext context;
	private final SwaggerConfiguration config;
	@Getter
	private Map<String, HandlerMapping> handlerMappingBeans;
	
	@Getter
	private Documentation resourceListing;
	
	private HashMap<String,DocumentationSchema> modelItems = new HashMap<String, DocumentationSchema>();
	
	private final Map<String,DocumentationEndPoint> resourceListCache = Maps.newHashMap();
	private final Map<String,ControllerDocumentation> apiCache = Maps.newHashMap();
    
	private static final List<RequestMethod> allRequestMethods = 
			Arrays.asList( RequestMethod.GET, RequestMethod.DELETE, RequestMethod.POST, RequestMethod.PUT );
	
	public MvcApiReader(WebApplicationContext context, SwaggerConfiguration swaggerConfiguration)
	{
		this.context = context;
		config = swaggerConfiguration;
		handlerMappingBeans = BeanFactoryUtils.beansOfTypeIncludingAncestors(this.context, HandlerMapping.class, true, false);
		buildModelListings();
		buildMappingDocuments();
	}
	
	private void buildMappingDocuments() {
		resourceListing = config.newDocumentation();
        
        resourceListing.setModels(modelItems);
		
		log.debug("Discovered {} candidates for documentation",handlerMappingBeans.size());
		for (HandlerMapping handlerMapping : handlerMappingBeans.values())
		{
			if (RequestMappingHandlerMapping.class.isAssignableFrom(handlerMapping.getClass()))
			{
				processMethod((RequestMappingHandlerMapping) handlerMapping);
			} else {
				log.debug("Not documenting mapping of type {}, as it is not of a recognized type.",handlerMapping.getClass().getName());
			}
		}
	}

	private void addApiListingIfMissing(
			MvcApiResource resource) {
		if (resourceListCache.containsKey(resource.getControllerUri()))
			return;
		
		DocumentationEndPoint endpoint = resource.describeAsEndpoint();
		if (endpoint != null)
		{
			resourceListCache.put(resource.getControllerUri(),endpoint);
			log.debug("Added resource listing: {}",resource.toString());
			resourceListing.addApi(endpoint);
		}
	}

	private void processMethod(RequestMappingHandlerMapping handlerMapping) {
		for (Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
			HandlerMethod handlerMethod = entry.getValue();
			RequestMappingInfo mappingInfo = entry.getKey();
			
			MvcApiResource resource = new MvcApiResource(handlerMethod,config);
			
			// Don't document our own controllers
			if (resource.isInternalResource())
				continue;
			
			if(resource.getControllerUri().contains("oauth") && !resource.getControllerClass().getName().contains("brandwatch")) {
			    continue;
			} 
			
			addApiListingIfMissing(resource);
			
			ControllerDocumentation apiDocumentation = getApiDocumentation(resource);
			apiDocumentation.setModels(modelItems);

			for (String requestUri : mappingInfo.getPatternsCondition().getPatterns())
			{
				DocumentationEndPoint endPoint = apiDocumentation.getEndPoint(requestUri);
				appendOperationsToEndpoint(mappingInfo,handlerMethod,endPoint);
				
			}
		}
	}

	private ControllerDocumentation getApiDocumentation(MvcApiResource resource) {
	    String apiCacheKey = resource.getControllerUri();
		if (!apiCache.containsKey(apiCacheKey))
		{
			ControllerDocumentation emptyApiDocumentation = resource.createEmptyApiDocumentation();
			if (emptyApiDocumentation != null)
				apiCache.put(apiCacheKey,emptyApiDocumentation);
		}
		return apiCache.get(apiCacheKey);
	}

	private void appendOperationsToEndpoint(
			RequestMappingInfo mappingInfo, HandlerMethod handlerMethod, DocumentationEndPoint endPoint) {
		ApiMethodReader methodDoc = new ApiMethodReader(handlerMethod);
		
		if (mappingInfo.getMethodsCondition().getMethods().isEmpty())
		{
			// no methods have been specified, it means the endpoint is accessible for all methods
			appendOperationsToEndpoint( methodDoc, endPoint, allRequestMethods );
		}
		else
		{
			appendOperationsToEndpoint( methodDoc,endPoint, mappingInfo.getMethodsCondition().getMethods() );
		}
	}
	
	private void appendOperationsToEndpoint(ApiMethodReader methodDoc, DocumentationEndPoint endPoint,
                                            Collection<RequestMethod> methods)
	{
		for (RequestMethod requestMethod : methods)
		{
			DocumentationOperation operation = methodDoc.getOperation(requestMethod);
			if(operation.getSummary() == null 
			        || (operation.getSummary() != null && !operation.getSummary().equals("##HIDDEN##")) ) {
                endPoint.addOperation(operation);
            }
		}
	}

	public ControllerDocumentation getDocumentation(
			String apiName) {
		
		for (ControllerDocumentation documentation : apiCache.values())
		{
			if (documentation.matchesName(apiName))
				return documentation;
		}
		log.error("Could not find a matching resource for api with name '" + apiName + "'");
		return null;
	}
	
	/*
	 * Build a listing for all API resources which are not controllers.
	 * 
	 * This listing is inserted into the model entry of each API resource.
	 */
	private void buildModelListings() {
	    
        Class<?> clazz = null;
        
	    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true);
	    
	    scanner.addIncludeFilter(new AnnotationTypeFilter(Api.class));
	    scanner.addExcludeFilter(new AnnotationTypeFilter(Controller.class));

	    /*
	     *  BW specific for efficiency, could be wided to the whole environment.
	     * Or read a property from the configuration
	     * (Might even be able to get hold of the base scan package from spring).
	     */
	    for (BeanDefinition bd : scanner.findCandidateComponents("com.brandwatch.webapp")) {
    	    try {
                clazz = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                log.debug("Class not found",e);
            }
    	    
    	    if(clazz != null) {
    	        DocumentationSchema modelDoc = buildModelItemListing(clazz);
    	        if(modelDoc != null) {
    	            modelItems.put(modelDoc.getId(), modelDoc);
    	        }
    	    }
	    }
	}

	/*
	 * Build a model entry for a class annotated with the @API
	 * annotation.
	 */
	
	private DocumentationSchema buildModelItemListing(Class<?> clazz) {

        Api apiAnno = clazz.getAnnotation(Api.class);
        ApiProperty prop = null;
        
        if(apiAnno != null) {
            
            DocumentationSchema modelDoc = new DocumentationSchema();
            modelDoc.setId(clazz.getSimpleName());
            modelDoc.setName(apiAnno.value());
            modelDoc.setDescription(apiAnno.description());
            
            Map<String,DocumentationSchema> properties = new HashMap<String, DocumentationSchema>();
            
            for(Method m : clazz.getMethods()) {
                prop = m.getAnnotation(ApiProperty.class);
                if(prop != null) {
                    DocumentationSchema propSchema = buildPropertiesListings(prop, m);
                    
                    properties.put(propSchema.getId(), propSchema);
                }
            }
            
            modelDoc.setProperties(properties);
            
            return modelDoc;
        }
        return null;
	}
	
	/*
	 * Build a listing for a method/property pair 
	 */
    private DocumentationSchema buildPropertiesListings(ApiProperty prop,Method m) {
        DocumentationSchema propSchema = new DocumentationSchema();
        
        if(m.getReturnType() == List.class){
            // This horrible piece of code is due to java making it difficult (impossible?!)
            // to access a class reference for the generic type of the return type.
            propSchema.setType("list<" + m.getGenericReturnType().toString().replaceFirst(".*?<", "").replaceAll(".*\\.", ""));
        } else {
            propSchema.setType(m.getReturnType().getSimpleName().toLowerCase());
        }
        propSchema.setDescription(prop.value());
        String name = m.getName().replaceFirst("get", "");
        name = Character.toLowerCase(name.charAt(0)) + (name.length() > 1 ? name.substring(1) : "");
        propSchema.setName(name);
        propSchema.setId(name);
        if(prop.allowableValues() != null && !prop.allowableValues().equals("")) {
            List<String> vals = Arrays.asList(prop.allowableValues().split(","));
            DocumentationAllowableValues v = new DocumentationAllowableListValues(vals);
            propSchema.setAllowableValues(v);
        }
        if(prop.notes().contains("required")) {
            propSchema.setRequired(true);
        } else {
            propSchema.setRequired(false);
        }
        
        return propSchema;
    }
	
}