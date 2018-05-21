package org.javarosa.xpath.expr;

import org.javarosa.xpath.expr.InFormCacheableExpr;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Aliza Stone
 */
public class InFormExpressionCacher {

    private Map<ExpressionCacheKey, Object> cache;

    public InFormExpressionCacher() {
        cache = new HashMap<>();
    }

    public void cache(ExpressionCacheKey cacheKey, Object value) {
        cache.put(cacheKey, value);
    }

    public Object getCachedValue(ExpressionCacheKey cacheKey) {
        return cache.get(cacheKey);
    }

}