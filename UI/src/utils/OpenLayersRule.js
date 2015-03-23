(function(root) {
  var contextFilter = function(attributeName, attributeValue, context) {
    return new OpenLayers.Filter.Function({ evaluate: function() {
      return context[attributeName] === attributeValue;
    } });
  };
  var featureAttributeFilter = function(attributeName, attributeValue) {
    return new OpenLayers.Filter.Comparison({
      type: OpenLayers.Filter.Comparison.EQUAL_TO,
      property: attributeName,
      value: attributeValue
    });
  };
  var useFunction = function(style, filters) {
    return new OpenLayers.Rule({
      filter: new OpenLayers.Filter.Logical({
        type: OpenLayers.Filter.Logical.AND,
        filters: filters
      }),
      symbolizer: style
    });
  };
  var createWhereFunction = function(state) {
    return function(attributeName, context) {
      return {
        is: function(attributeValue) {
          var ret = state;
          var filter = context ? contextFilter(attributeName, attributeValue, context) :
            featureAttributeFilter(attributeName, attributeValue);
          ret.filters = ret.filters.concat([filter]);
          ret.use = function(style) {
            return useFunction(style, ret.filters);
          };
          return state;
        }
      };
    };
  };
  root.OpenLayersRule = function() {
    var state = {
      filters: []
    };
    return {
      where: createWhereFunction(state)
    };
  };
})(this);
