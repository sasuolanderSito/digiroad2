(function(root) {
  root.MassTransitStop = function(data) {
    var cachedMarker = null;
    var cachedDirectionArrow = null;
    var cachedMassTransitMarker = null;

    var createDirectionArrow = function() {
      var getAngleFromBearing = function(bearing, validityDirection) {
        if (bearing === null || bearing === undefined) {
          console.log('Bearing was null, find out why');
          return 90;
        }
        return bearing + (90 * validityDirection);
      };
      var validityDirection = (data.validityDirection === validitydirections.oppositeDirection) ? 1 : -1;
      var angle = getAngleFromBearing(data.bearing, validityDirection);
      return new OpenLayers.Feature.Vector(
        new OpenLayers.Geometry.Point(data.group ? data.group.lon : data.lon, data.group ? data.group.lat : data.lat),
        null,
        {
          externalGraphic: 'src/resources/digiroad2/bundle/assetlayer/images/direction-arrow.svg',
          graphicHeight: 16, graphicWidth: 30, graphicXOffset: -15, graphicYOffset: -8, rotation: angle
        }
      );
    };

    var getMarker = function(shouldCreate) {
      if (shouldCreate || !cachedMarker) {
        cachedMassTransitMarker = new MassTransitMarker(data);
        cachedMarker = cachedMassTransitMarker.createMarker();
      }
      return cachedMarker;
    };

    var createNewMarker = function() {
      cachedMassTransitMarker = new MassTransitMarker(data);
      cachedMarker = cachedMassTransitMarker.createNewMarker();
      return cachedMarker;
    };

    var getMassTransitMarker = function() {
      return cachedMassTransitMarker;
    };

    var getDirectionArrow = function(shouldCreate) {
      if (shouldCreate || !cachedDirectionArrow) {
        cachedDirectionArrow = createDirectionArrow();
      }
      return cachedDirectionArrow;
    };

    var moveTo = function(lonlat) {
      getDirectionArrow().move(lonlat);
      getMassTransitMarker().moveTo(lonlat);
    };

    var select = function() { getMassTransitMarker().select(); };

    var deselect = function() { getMassTransitMarker().deselect(); };

    var finalizeMove = function() {
      getMassTransitMarker().finalizeMove();
    };

    var rePlaceInGroup = function() { getMassTransitMarker().rePlaceInGroup(); };

    return {
      getMarker: getMarker,
      createNewMarker: createNewMarker,
      getDirectionArrow: getDirectionArrow,
      moveTo: moveTo,
      select: select,
      deselect: deselect,
      finalizeMove: finalizeMove,
      rePlaceInGroup: rePlaceInGroup
    };
  };
}(this));
