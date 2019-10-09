/*******************************************************************************
 * (c) Copyright 2017 EntIT Software LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a 
 * copy of this software and associated documentation files (the 
 * "Software"), to deal in the Software without restriction, including without 
 * limitation the rights to use, copy, modify, merge, publish, distribute, 
 * sublicense, and/or sell copies of the Software, and to permit persons to 
 * whom the Software is furnished to do so, subject to the following 
 * conditions:
 * 
 * The above copyright notice and this permission notice shall be included 
 * in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY 
 * KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE 
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR 
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE 
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, 
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN 
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS 
 * IN THE SOFTWARE.
 ******************************************************************************/
package com.fortify.ssc.parser.sarif.parser;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDelegatingDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fortify.plugin.api.ScanData;
import com.fortify.plugin.api.ScanParsingException;
import com.fortify.ssc.parser.sarif.parser.util.DateConverter;
import com.fortify.ssc.parser.sarif.parser.util.Region;
import com.fortify.ssc.parser.sarif.parser.util.RegionInputStream;

/**
 * Abstract base class for parsing arbitrary JSON structures. 
 * TODO Add more information/examples how to use the various
 *      parse methods.
 * 
 * @author Ruud Senden
 *
 */
public abstract class AbstractParser {
	private static final Logger LOG = LoggerFactory.getLogger(AbstractParser.class);
	private static final JsonFactory JSON_FACTORY = new JsonFactory().disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
	protected static final DateConverter DATE_CONVERTER = new DateConverter();
	protected final ObjectMapper objectMapper = getObjectMapper();
	private final Map<String, Handler> pathToHandlerMap = new LinkedHashMap<>();

	/**
	 * Default constructor that initializes the parser handlers by calling the
	 * {@link AbstractParser#AbstractParser(boolean)} constructor with 'true'
	 * parameter.
	 */
	public AbstractParser() {
		this(true);
	}
	
	/**
	 * Subclasses can call this constructor with the parameter set to
	 * 'false' if they need to perform some initialization before initializing
	 * the handlers. If this constructor is called with a 'false' parameter,
	 * the subclass is responsible for calling {@link #initializeHandlers()}.
	 * @param initializeHandlers true if handlers should be initialized,
	 *        false if subclass will call {@link #initializeHandlers()} itself
	 *        from its constructor.
	 */
	protected AbstractParser(boolean initializeHandlers) {
		if ( initializeHandlers ) { initializeHandlers(); }
	}

	/**
	 * This method initializes the handlers for the current parser instance by calling the 
	 * following methods:
	 * <ul>
	 *  <li>{@link #addHandlers(Map)}: Allows subclasses to add arbitrary JSON element handlers</li>
	 *  <li>{@link #addPropertyHandlers(Map, Object)}: Adds default handlers for properties 
	 *      annotated with {@link JsonProperty} defined in the current parser instance</li>
	 *  <li>{@link #addParentHandlers(Map)}: Adds intermediate handlers for traversing the
	 *      path to nested properties, based on property paths defined through the previous
	 *      two methods</li> 
	 * </ul>
	 */
	protected final void initializeHandlers() {
		addHandlers(pathToHandlerMap);
		addPropertyHandlers(pathToHandlerMap, this); 
		addParentHandlers(pathToHandlerMap);
	}
	
	/**
	 * This method returns an {@link ObjectMapper} instance used for
	 * mapping JSON data to Java objects/values. This default implementation
	 * uses the default {@link ObjectMapper} implementation, but adds our
	 * {@link DateConverter} for converting date strings. Subclasses may
	 * override this method to add additional converters.
	 * @return
	 */
	protected ObjectMapper getObjectMapper() {
		ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		SimpleModule module = new SimpleModule();
		module.addDeserializer(Date.class, new StdDelegatingDeserializer<Date>(DATE_CONVERTER));
		mapper.registerModule(module);
		return mapper;
	}

	/**
	 * Subclasses can override this method to add mappings between JSON property paths
	 * and corresponding {@link Handler} instances to process the corresponding property 
	 * value. Property paths are specified as <code>/rootObjectName/childObjectName/propertyName</code>.
	 * Handlers are usually provided using a lambda expression, for example 
	 * <code>jp->System.out.println(jp.getValueAsString())</code>.
	 * 
	 * @param pathToHandlerMap Implementations can add property path and corresponding
	 *        handler to this map using <code>pathToHandlerMap.put(propertyPath, handler)</code> 
	 */
	protected void addHandlers(Map<String, Handler> pathToHandlerMap) {}
	
	/**
	 * For every property path defined in the given pathToHandlerMap, this method
	 * will add intermediate handlers to traversing parent objects and arrays.
	 * 
	 * @param pathToHandlerMap
	 */
	private final void addParentHandlers(Map<String, Handler> pathToHandlerMap) {
		Set<String> keySet = new HashSet<>(pathToHandlerMap.keySet()); 
		for ( String key : keySet ) {
			LOG.debug("Adding parent handlers for "+key);
			String[] pathElts = key.split("/");
			String currentPath = "";
			for ( String pathElt : pathElts ) {
				currentPath = getPath(currentPath, pathElt);
				addParentHandler(pathToHandlerMap, currentPath);
			}
		}
	}
	
	/**
	 * This method adds an intermediate handler for traversing into the given path.
	 * @param pathToHandlerMap
	 * @param path
	 */
	private final void addParentHandler(final Map<String, Handler> pathToHandlerMap, final String path) {
		if ( !pathToHandlerMap.containsKey(path) ) {
			LOG.debug("Adding parent handler for "+path);
			pathToHandlerMap.put(path, jsonParser->parseObjectOrArrayChildren(jsonParser, path));
		}
	}
	
	/**
	 * For every property annotated with {@link JsonProperty} in the class for the given object,
	 * this method will add a corresponding handler to set the property value on the given object.
	 * The property path can be specified as the {@link JsonProperty} annotation value. If no 
	 * property path is specified, the property path /fieldName will be used.
	 * @param pathToHandlerMap
	 * @param object
	 */
	protected final void addPropertyHandlers(Map<String, Handler> pathToHandlerMap, Object object) {
    	Field[] fields = FieldUtils.getFieldsWithAnnotation(object.getClass(), JsonProperty.class);
    	for ( Field field : fields ) {
    		String path = field.getAnnotation(JsonProperty.class).value();
    		if (StringUtils.isBlank(path)) { path = "/"+field.getName().replace('_', '/'); }
    		LOG.debug("Adding property handler for property path "+path);
    		pathToHandlerMap.put(path, jp->{
				try {
					Object value = objectMapper.readValue(jp, field.getType());
					FieldUtils.writeField(field, object, value, true);
				} catch (IllegalAccessException e) {
					throw new RuntimeException("Error setting field "+field.getName(), e);
				}
			});
    	}
    }

	/**
	 * Parse JSON contents retrieved from the given {@link ScanData} object,
	 * calling the {@link #finish()} method once parsing has been completed.
	 * @param scanData
	 * @throws ScanParsingException
	 * @throws IOException
	 */
	public final void parse(ScanData scanData) throws ScanParsingException, IOException {
		parse(scanData, null);
	}
	
	/**
	 * Parse JSON contents retrieved from the given {@link ScanData} object
	 * for the given input region, calling the {@link #finish()} method once 
	 * parsing has been completed.
	 * @param scanData
	 * @throws ScanParsingException
	 * @throws IOException
	 */
	public final void parse(ScanData scanData, Region inputRegion) throws ScanParsingException, IOException {
		try (   final InputStream content = new RegionInputStream(
					scanData.getInputStream(x -> x.endsWith(".sarif")),
					inputRegion);
				final JsonParser jsonParser = JSON_FACTORY.createParser(content)) {
			jsonParser.nextToken();
			assertParseStart(jsonParser);
			parse(jsonParser, "/");
			finish();
		}
	}
	
	/**
	 * This method is called by the {@link #parse(ScanData, Region)} method (and indirectly
	 * by the {@link #parse(ScanData)} method) to verify that the given input document or
	 * region starts with an expected JSON element type. By default, this method asserts 
	 * that the given {@link JsonParser} points at the start of a JSON object. Subclasses
	 * can override this method if they expect the document/region to start with another
	 * JSON element, like a JSON array.
	 * @param jsonParser
	 * @throws ScanParsingException
	 */
	protected void assertParseStart(JsonParser jsonParser) throws ScanParsingException {
		assertStartObject(jsonParser);
	}

	/**
	 * This method simply calls the {@link #parse(JsonParser, String)} method
	 * to parse the current JSON element, and then calls the {@link #finish()}
	 * method.
	 * 
	 * @param jsonParser
	 * @param parentPath
	 * @throws ScanParsingException
	 * @throws IOException
	 */
	public final void parseAndFinish(final JsonParser jsonParser, String parentPath) throws ScanParsingException, IOException {
		parse(jsonParser, parentPath);
		finish();
	}

	/**
	 * This method checks whether a {@link Handler} has been registered for the 
	 * current JSON element. If a {@link Handler} is found, this method will simply
	 * invoke the {@link Handler} to parse the contents of the current JSON element.
	 * If no {@link Handler} is found, this method will skip all children of the
	 * current JSON element.
	 * 
	 * This method simply returns after handling the current JSON elements; recursive
	 * parsing is handled by registered {@link Handler} instances.
	 * 
	 * @param jsonParser
	 * @param parentPath
	 * @throws ScanParsingException
	 * @throws IOException
	 */
	protected final void parse(final JsonParser jsonParser, String parentPath) throws ScanParsingException, IOException {
		JsonToken currentToken = jsonParser.getCurrentToken();
		if ( currentToken != null && (currentToken==JsonToken.START_ARRAY || currentToken==JsonToken.START_OBJECT || currentToken.isScalarValue())) {
			String currentPath = getPath(parentPath, jsonParser.getCurrentName());
			LOG.trace("Processing "+currentPath);
			Handler handler = pathToHandlerMap.computeIfAbsent(currentPath, k->pathToHandlerMap.get(getPath(parentPath, "*")));
			if ( handler != null ) {
				LOG.debug("Handling "+currentPath);
				handler.handle(jsonParser);
			} else {
				skipChildren(jsonParser);
			}
		}
	}
	
	/**
	 * Append the given currentName to the given parentPath,
	 * correctly handling the separator.
	 * 
	 * @param parentPath
	 * @param currentName
	 * @return
	 */
	protected final String getPath(String parentPath, String currentName) {
		String result = parentPath;
		if ( currentName!=null ) {
			result+=result.endsWith("/")?"":"/";
			result+=currentName;
		}
		if ( "".equals(result) ) { result="/"; }
		return result;
	}

	/**
	 * Parse the children of the current JSON object or JSON array.
	 * 
	 * @param jsonParser
	 * @param currentPath
	 * @throws ScanParsingException
	 * @throws IOException
	 */
	protected final void parseObjectOrArrayChildren(JsonParser jsonParser, String currentPath) throws ScanParsingException, IOException {
		JsonToken currentToken = jsonParser.getCurrentToken();
		if ( currentToken==JsonToken.START_OBJECT ) {
			parseObjectProperties(jsonParser, currentPath);
		} else if ( currentToken==JsonToken.START_ARRAY ) {
			parseArrayEntries(jsonParser, currentPath);
		}
	}
	
	/**
	 * Parse the individual object properties of the current JSON object.
	 * 
	 * @param jsonParser
	 * @param currentPath
	 * @throws ScanParsingException
	 * @throws IOException
	 */
	protected final void parseObjectProperties(JsonParser jsonParser, String currentPath) throws ScanParsingException, IOException {
		parseChildren(jsonParser, currentPath, JsonToken.END_OBJECT);
	}
	
	/**
	 * Parse the individual object properties of the current JSON object
	 * by calling the {@link #parseObjectProperties(JsonParser, String)}
	 * method, then call the {@link #finish()} method once parsing has
	 * finished.
	 * 
	 * @param jsonParser
	 * @param currentPath
	 * @throws ScanParsingException
	 * @throws IOException
	 */
	public final void parseObjectPropertiesAndFinish(JsonParser jsonParser, String currentPath) throws ScanParsingException, IOException {
		parseChildren(jsonParser, currentPath, JsonToken.END_OBJECT);
		finish();
	}
	
	/**
	 * Parse the individual array entries of the current JSON array.
	 * 
	 * @param jsonParser
	 * @param currentPath
	 * @throws ScanParsingException
	 * @throws IOException
	 */
	protected final void parseArrayEntries(JsonParser jsonParser, String currentPath) throws ScanParsingException, IOException {
		parseChildren(jsonParser, currentPath, JsonToken.END_ARRAY);
	}
	
	/**
	 * Parse the children of the current JSON element, up to the given endToken
	 * 
	 * @param jsonParser
	 * @param currentPath
	 * @param endToken
	 * @throws ScanParsingException
	 * @throws IOException
	 */
	private final void parseChildren(JsonParser jsonParser, String currentPath, JsonToken endToken) throws ScanParsingException, IOException {
		while (jsonParser.nextToken()!=endToken) {
			parse(jsonParser, currentPath);
		}
	}
	
	/**
	 * Subclasses can override this method if they need to perform any processing once
	 * parsing has finished.
	 * @return
	 * @throws IOException 
	 * @throws ScanParsingException 
	 */
	protected <T> T finish() throws ScanParsingException, IOException { return null; }
	
	/**
	 * Assuming the given {@link JsonParser} instance is currently pointing at a JSON array
	 * that contains JSON objects, for each array entry this method will:
	 * <ul>
	 *  <li>Request an {@link AbstractParser} instance from the given {@link ParserSupplier}</li>
	 *  <li>Invoke the {@link AbstractParser#parseObjectProperties(JsonParser, String)} method
	 *      on the current array entry</li>
	 *  <li>Invoke the {@link AbstractParser#finish()} method
	 * </ul> 
	 * @param jsonParser
	 * @param parserFactory
	 * @throws ScanParsingException Thrown if the given JsonParser does not
	 *         point at the start of an array, or the array contains non-object
	 *         entries (i.e. contains scalar values or other arrays)
	 * @throws IOException
	 */
	protected static final void parseArrayEntries(JsonParser jsonParser, ParserSupplier<? extends AbstractParser> parserFactory) throws ScanParsingException, IOException {
		assertStartArray(jsonParser);
		while (jsonParser.nextToken()!=JsonToken.END_ARRAY) {
			assertStartObject(jsonParser);
			AbstractParser abstractParser = parserFactory.get();
			abstractParser.parseObjectPropertiesAndFinish(jsonParser, "/");
		}
	}
	
	/**
	 * Assuming the given {@link JsonParser} instance is currently pointing at a JSON array,
	 * this method will return the number of array entries.
	 * 
	 * @param jsonParser
	 * @return
	 * @throws ScanParsingException Thrown if the given JsonParser does not
	 *         point at the start of an array
	 * @throws IOException
	 */
	protected static final int countArrayEntries(JsonParser jsonParser) throws ScanParsingException, IOException {
		assertStartArray(jsonParser);
		int result = 0;
		while (jsonParser.nextToken()!=JsonToken.END_ARRAY) {
			result++;
			skipChildren(jsonParser);
		}
		return result;
	}
	
	/**
	 * Assuming the given {@link JsonParser} instance is currently pointing at a JSON object,
	 * this method will return the number of object entries.
	 * 
	 * @param jsonParser
	 * @return
	 * @throws ScanParsingException Thrown if the given JsonParser does not
	 *         point at the start of an object
	 * @throws IOException
	 */
	protected static final int countObjectEntries(JsonParser jsonParser) throws ScanParsingException, IOException {
		assertStartObject(jsonParser);
		int result = 0;
		while (jsonParser.nextToken()!=JsonToken.END_OBJECT) {
			result++;
			skipChildren(jsonParser);
		}
		return result;
	}
	
	/**
	 * Get the region (start and end position) of the current JSON element.
	 * 
	 * @param jsonParser
	 * @return
	 * @throws ScanParsingException
	 * @throws IOException
	 */
	protected static final Region getObjectOrArrayRegion(JsonParser jsonParser) throws ScanParsingException, IOException {
		assertStartObjectOrArray(jsonParser);
		// TODO Do we need to take into account file encoding to determine number of bytes
		//      for the '[' character?
		long start = jsonParser.getCurrentLocation().getByteOffset()-"[".getBytes().length; 
		jsonParser.skipChildren();
		long end = jsonParser.getCurrentLocation().getByteOffset();
		return new Region(start, end);
	}
	
	/**
	 * If the given {@link JsonParser} is currently pointing at a JSON object or array,
	 * this method will skip all children of that object or array.
	 * @param jsonParser
	 * @throws IOException
	 */
	protected static final void skipChildren(final JsonParser jsonParser) throws IOException {
		switch (jsonParser.getCurrentToken()) {
		case START_ARRAY:
		case START_OBJECT:
			LOG.trace("Skipping children");
			jsonParser.skipChildren();
			break;
		default: break;
		}
	}
	
	/**
	 * Assert that the given {@link JsonParser} is currently pointing at the start tag of a 
	 * JSON array, throwing a {@link ScanParsingException} otherwise.
	 * @param jsonParser
	 * @throws ScanParsingException
	 */
	protected static final void assertStartArray(final JsonParser jsonParser) throws ScanParsingException {
		if (jsonParser.currentToken() != JsonToken.START_ARRAY) {
			throw new ScanParsingException(String.format("Expected array start at %s", jsonParser.getTokenLocation()));
		}
	}

	/**
	 * Assert that the given {@link JsonParser} is currently pointing at the start tag of a 
	 * JSON object, throwing a {@link ScanParsingException} otherwise.
	 * @param jsonParser
	 * @throws ScanParsingException
	 */
	protected static final void assertStartObject(final JsonParser jsonParser) throws ScanParsingException {
		if (jsonParser.currentToken() != JsonToken.START_OBJECT) {
			throw new ScanParsingException(String.format("Expected object start at %s", jsonParser.getTokenLocation()));
		}
	}
	
	/**
	 * Assert that the given {@link JsonParser} is currently pointing at the start tag of a 
	 * JSON object or array, throwing a {@link ScanParsingException} otherwise.
	 * @param jsonParser
	 * @throws ScanParsingException
	 */
	protected static final void assertStartObjectOrArray(final JsonParser jsonParser) throws ScanParsingException {
		if (jsonParser.currentToken() != JsonToken.START_OBJECT && jsonParser.currentToken() != JsonToken.START_ARRAY) {
			throw new ScanParsingException(String.format("Expected object or array start at %s", jsonParser.getTokenLocation()));
		}
	}

	/**
	 * Functional interface for handling the JSON element that the given
	 * {@link JsonParser} is pointing at.
	 * 
	 * @author Ruud Senden
	 */
	@FunctionalInterface
	public interface Handler {
	    void handle(JsonParser jsonParser) throws ScanParsingException, IOException;
	}
	
	/**
	 * Functional interface for supplying {@link AbstractParser} instances.
	 * Contrary to the standard {@link Supplier} interface, this interface
	 * allows the {@link #get()} method to throw {@link ScanParsingException} 
	 * or {@link IOException}.
	 * 
	 * @author Ruud Senden
	 *
	 * @param <T>
	 */
	@FunctionalInterface
	public interface ParserSupplier<T extends AbstractParser> {
	    T get() throws ScanParsingException, IOException;
	}
}