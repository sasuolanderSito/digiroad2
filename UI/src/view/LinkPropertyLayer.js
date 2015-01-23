(function(root) {
  root.LinkPropertyLayer = function(map, roadLayer, geometryUtils, selectedLinkProperty) {
    var administrativeClassStyleLookup = {
      Private: { strokeColor: '#0011bb', externalGraphic: 'images/link-properties/privateroad.svg' },
      Municipality: { strokeColor: '#11bb00', externalGraphic: 'images/link-properties/street.svg' },
      State: { strokeColor: '#ff0000', externalGraphic: 'images/link-properties/road.svg' }
    };

    var oneWaySignSizeLookup = {
      9: { pointRadius: 0 },
      10: { pointRadius: 12 },
      11: { pointRadius: 14 },
      12: { pointRadius: 16 },
      13: { pointRadius: 20 },
      14: { pointRadius: 24 },
      15: { pointRadius: 24 }
    };

    var defaultStyle = new OpenLayers.Style(OpenLayers.Util.applyDefaults({
      strokeOpacity: 0.7,
      rotation: '${rotation}'
    }));

    var defaultStyleMap = new OpenLayers.StyleMap({ 'default': defaultStyle });

    var combineFilters = function(filters) {
      return new OpenLayers.Filter.Logical({ type: OpenLayers.Filter.Logical.AND, filters: filters });
    };

    var functionalClassFilter = function(functionalClass) {
      return new OpenLayers.Filter.Comparison({ type: OpenLayers.Filter.Comparison.EQUAL_TO, property: 'functionalClass', value: functionalClass });
    };

    var strokeWidthStyle = function(zoomLevel, functionalClass, symbolizer) {
      return new OpenLayers.Rule({
        filter: combineFilters([functionalClassFilter(functionalClass), roadLayer.createZoomLevelFilter(zoomLevel)]),
        symbolizer: symbolizer
      });
    };

    var createStrokeWidthStyles = function() {
      var strokeWidthsByZoomLevelAndFunctionalClass = {
        9: [ 10, 9, 8, 7, 6, 5, 4, 3 ],
        10: [ 18, 15, 12, 9, 7, 4, 2, 1 ],
        11: [ 20, 16, 12, 9, 7, 4, 2, 1 ],
        12: [ 25, 21, 17, 13, 9, 5, 2, 1 ],
        13: [ 32, 26, 20, 14, 9, 5, 2, 1 ],
        14: [ 32, 26, 20, 14, 9, 5, 2, 1 ],
        15: [ 32, 26, 20, 14, 9, 5, 2, 1 ]
      };

      return _.chain(strokeWidthsByZoomLevelAndFunctionalClass).map(function(widthsByZoomLevel, zoomLevel) {
        return _.map(widthsByZoomLevel, function(width, index) {
          var functionalClass = index + 1;
          return strokeWidthStyle(parseInt(zoomLevel, 10), functionalClass, { strokeWidth: width });
        });
      }).flatten().value();
    };

    roadLayer.addUIStateDependentLookupToStyleMap(defaultStyleMap, 'default', 'zoomLevel', oneWaySignSizeLookup);
    defaultStyleMap.addUniqueValueRules('default', 'administrativeClass', administrativeClassStyleLookup);
    defaultStyle.addRules(createStrokeWidthStyles());
    roadLayer.setLayerSpecificStyleMap('linkProperties', defaultStyleMap);

    var selectionStyleMap = new OpenLayers.StyleMap({
      'select': new OpenLayers.Style(OpenLayers.Util.applyDefaults({
        strokeOpacity: 0.7,
        graphicOpacity: 1.0,
        rotation: '${rotation}'
      })),
      'default': new OpenLayers.Style(OpenLayers.Util.applyDefaults({
        strokeOpacity: 0.3,
        graphicOpacity: 0.3,
        rotation: '${rotation}'
      }))
    });
    roadLayer.addUIStateDependentLookupToStyleMap(selectionStyleMap, 'default', 'zoomLevel', RoadLayerSelectionStyle.linkSizeLookup);
    roadLayer.addUIStateDependentLookupToStyleMap(selectionStyleMap, 'default', 'zoomLevel', oneWaySignSizeLookup);
    roadLayer.addUIStateDependentLookupToStyleMap(selectionStyleMap, 'select', 'zoomLevel', RoadLayerSelectionStyle.linkSizeLookup);
    roadLayer.addUIStateDependentLookupToStyleMap(selectionStyleMap, 'select', 'zoomLevel', oneWaySignSizeLookup);
    selectionStyleMap.addUniqueValueRules('default', 'administrativeClass', administrativeClassStyleLookup);
    selectionStyleMap.addUniqueValueRules('select', 'administrativeClass', administrativeClassStyleLookup);
    selectionStyleMap.styles.select.addRules(createStrokeWidthStyles());
    selectionStyleMap.styles.default.addRules(createStrokeWidthStyles());

    var selectControl = new OpenLayers.Control.SelectFeature(roadLayer.layer, {
      onSelect:  function(feature) {
        selectedLinkProperty.open(feature.attributes.roadLinkId);
        roadLayer.setLayerSpecificStyleMap('linkProperties', selectionStyleMap);
        roadLayer.layer.redraw();
        highlightFeatures(feature);
      },
      onUnselect: function() {
        deselectRoadLink();
        roadLayer.layer.redraw();
        highlightFeatures(null);
      }
    });
    map.addControl(selectControl);

    var eventListener = _.extend({running: false}, eventbus);

    var highlightFeatures = function(feature) {
      _.each(roadLayer.layer.features, function(x) {
        if (feature && (x.attributes.roadLinkId === feature.attributes.roadLinkId)) {
          selectControl.highlight(x);
        } else {
          selectControl.unhighlight(x);
        }
      });
    };

    var handleMapMoved = function(state) {
      if (zoomlevels.isInRoadLinkZoomLevel(state.zoom) && state.selectedLayer === 'linkProperties') {
        start();
      } else if (selectedLinkProperty.isDirty()) {
        displayConfirmMessage();
      } else {
        stop();
      }
    };

    var drawOneWaySigns = function(roadLinks) {
      var oneWaySigns = _.chain(roadLinks)
        .filter(function(link) {
          return link.trafficDirection === 'AgainstDigitizing' || link.trafficDirection === 'TowardsDigitizing';
        })
        .map(function(link) {
          var points = _.map(link.points, function(point) {
            return new OpenLayers.Geometry.Point(point.x, point.y);
          });
          var lineString = new OpenLayers.Geometry.LineString(points);
          var signPosition = geometryUtils.calculateMidpointOfLineString(lineString);
          var rotation = link.trafficDirection === 'AgainstDigitizing' ? signPosition.angleFromNorth + 180.0 : signPosition.angleFromNorth;
          var attributes = _.merge({}, link, { rotation: rotation });
          return new OpenLayers.Feature.Vector(new OpenLayers.Geometry.Point(signPosition.x, signPosition.y), attributes);
        })
        .value();

      roadLayer.layer.addFeatures(oneWaySigns);
    };

    var reselectRoadLink = function() {
      selectControl.activate();
      var originalOnSelectHandler = selectControl.onSelect;
      selectControl.onSelect = function() {};
      var feature = _.find(roadLayer.layer.features, function(feature) { return feature.attributes.roadLinkId === selectedLinkProperty.getId(); });
      if (feature) {
        selectControl.select(feature);
        highlightFeatures(feature);
      }
      selectControl.onSelect = originalOnSelectHandler;
      if (selectedLinkProperty.get() && selectedLinkProperty.isDirty()) {
        selectControl.deactivate();
      }
    };

    var deselectRoadLink = function() {
      roadLayer.setLayerSpecificStyleMap('linkProperties', defaultStyleMap);
      selectedLinkProperty.close();
    };

    var prepareRoadLinkDraw = function() {
      selectControl.deactivate();
    };

    eventbus.on('map:moved', handleMapMoved);

    var start = function() {
      if (!eventListener.running) {
        eventListener.running = true;
        eventListener.listenTo(eventbus, 'roadLinks:beforeDraw', prepareRoadLinkDraw);
        eventListener.listenTo(eventbus, 'roadLinks:afterDraw', function(roadLinks) {
          drawOneWaySigns(roadLinks);
          reselectRoadLink();
        });
        eventListener.listenTo(eventbus, 'linkProperties:changed', handleLinkPropertyChanged);
        eventListener.listenTo(eventbus, 'linkProperties:cancelled linkProperties:saved', concludeLinkPropertyEdit);
        selectControl.activate();
      }
    };


    var displayConfirmMessage = function() { new Confirm(); };

    var handleLinkPropertyChanged = function() {
      redrawSelected();
      selectControl.deactivate();
      eventListener.stopListening(eventbus, 'map:clicked', displayConfirmMessage);
      eventListener.listenTo(eventbus, 'map:clicked', displayConfirmMessage);
    };

    var concludeLinkPropertyEdit = function() {
      selectControl.activate();
      eventListener.stopListening(eventbus, 'map:clicked', displayConfirmMessage);
      redrawSelected();
    };

    var redrawSelected = function() {
      var selectedFeatures = _.filter(roadLayer.layer.features, function(feature) {
        return feature.attributes.roadLinkId === selectedLinkProperty.getId();
      });
      roadLayer.layer.removeFeatures(selectedFeatures);
      var data = selectedLinkProperty.get().getData();
      roadLayer.drawRoadLink(data);
      drawOneWaySigns([data]);
      reselectRoadLink();
    };

    var stop = function() {
      selectControl.deactivate();
      eventListener.stopListening(eventbus);
      eventListener.running = false;
    };

    var show = function(map) {
      if (zoomlevels.isInRoadLinkZoomLevel(map.getZoom())) {
        start();
      }
    };

    var hide = function() {
      stop();
      deselectRoadLink();
    };

    return {
      show: show,
      hide: hide,
      minZoomForContent: zoomlevels.minZoomForRoadLinks
    };
  };
})(this);
