(function(selectedMassTransitStop) {
  selectedMassTransitStop.initialize = function(backend) {
    var usedKeysFromFetchedAsset = [
      'bearing',
      'lat',
      'lon',
      'roadLinkId',
      'linkId',
      'nationalId',
      'validityDirection',
      'floating'];
    var assetHasBeenModified = false;
    var currentAsset = {};
    var changedProps = [];

    var close = function() {
      assetHasBeenModified = false;
      currentAsset = {};
      changedProps = [];
      eventbus.trigger('asset:closed');
    };

    eventbus.on('tool:changed', function(tool) {
      if (tool !== 'Add' && exists()) {
        backend.getMassTransitStopByNationalId(currentAsset.payload.nationalId, function(asset) {
          if (exists()) { eventbus.trigger('asset:fetched', asset); }
        });
      }
    });

    var transformPropertyData = function(propertyData) {
      var transformValues = function(publicId, values) {
        var transformValue = function(value) {
          return {
            propertyValue: value.propertyValue,
            propertyDisplayValue: value.propertyDisplayValue
          };
        };

        return _.map(values.values, transformValue);
      };
      var transformProperty = function(property) {
        return _.merge(
          {},
          _.pick(property, 'publicId', 'propertyType', 'required'),
          {
            values: transformValues(_.pick(property, 'publicId'), _.pick(property, 'values'))
          });
      };
      return {
        properties: _.map(propertyData, transformProperty)
      };
    };

    var extractPublicIds = function(properties) {
      return _.map(properties, function(property) { return property.publicId; });
    };

    var updatePropertyData = function(properties, propertyData) {
      return _.reject(properties, function(property) { return property.publicId === propertyData.publicId; }).concat([propertyData]);
    };

    var pickProperties = function(properties, publicIds) {
      return _.filter(properties, function(property) { return _.contains(publicIds, property.publicId); });
    };

    var payloadWithProperties = function(payload, publicIds) {
      return _.merge(
        {},
        _.pick(payload, function(value, key) { return key != 'properties'; }),
        {
          properties: pickProperties(payload.properties, publicIds)
        });
    };

    var place = function(asset) {
      eventbus.trigger('asset:placed', asset);
      currentAsset = asset;
      currentAsset.payload = {};
      assetHasBeenModified = true;
      backend.getAssetTypeProperties(function(properties) {
        currentAsset.propertyMetadata = properties;
        currentAsset.payload = _.merge({}, _.pick(currentAsset, usedKeysFromFetchedAsset), transformPropertyData(properties));
        changedProps = extractPublicIds(currentAsset.payload.properties);
        eventbus.trigger('asset:modified');
      });
    };

    var move = function(position) {
      currentAsset.payload.bearing = position.bearing;
      currentAsset.payload.lon = position.lon;
      currentAsset.payload.lat = position.lat;
      currentAsset.payload.roadLinkId = position.roadLinkId;
      currentAsset.payload.linkId = position.linkId;
      assetHasBeenModified = true;
      changedProps = _.union(changedProps, ['bearing', 'lon', 'lat', 'roadLinkId']);
      eventbus.trigger('asset:moved', position);
    };

    var cancel = function() {
      changedProps = [];
      assetHasBeenModified = false;
      if (currentAsset.id) {
        backend.getMassTransitStopByNationalId(currentAsset.payload.nationalId, function(asset) {
          open(asset);
          eventbus.trigger('asset:updateCancelled', asset);
        });
      } else {
        currentAsset = {};
        eventbus.trigger('asset:creationCancelled');
      }
    };

    eventbus.on('application:readOnly', function() {
      if (exists()) {
        backend.getMassTransitStopByNationalId(currentAsset.payload.nationalId, function(asset) {
          if (exists()) { eventbus.trigger('asset:fetched', asset); }
        });
      }
    });

    eventbus.on('validityPeriod:changed', function(validityPeriods) {
      if (currentAsset && (!_.contains(validityPeriods, currentAsset.validityPeriod) &&
        currentAsset.validityPeriod !== undefined)) {
        close();
      }
    });

    eventbus.on('asset:saved asset:created', function() {
      changedProps = [];
      assetHasBeenModified = false;
    });
    eventbus.on('asset:created', function(asset) {
      currentAsset.id = asset.id;
      open(asset);
    });

    var open = function(asset) {
      currentAsset.id = asset.id;
      currentAsset.propertyMetadata = asset.propertyData;
      currentAsset.payload = _.merge({}, _.pick(asset, usedKeysFromFetchedAsset), transformPropertyData(asset.propertyData));
      currentAsset.validityPeriod = asset.validityPeriod;
      eventbus.trigger('asset:modified');
    };

    eventbus.on('asset:fetched', open, this);

    var getProperties = function() {
      return currentAsset.payload.properties;
    };

    var getPropertyMetadata = function(publicId) {
      return _.find(currentAsset.propertyMetadata, function(metadata) {
        return metadata.publicId === publicId;
      });
    };

    var mixedVirtualAndRealStops = function()
    {
      var isVirtualStopsMixed = function (property)
      {
        var arraysize = property.values.length;
        for (var n = 0; arraysize > n; n++)
        {
          var currentPropertyValue = property.values[n].propertyValue;
          if (currentPropertyValue == 5)
          {
            return  (arraysize > 1);
          }
        }
        return false;
      };
      var mixed=false;
      _.some(currentAsset.payload.properties, function(property)
      {
        if (property.publicId == "pysakin_tyyppi")
        {
          mixed=isVirtualStopsMixed(property);
        }
      });
      return mixed;
    };

    var requiredPropertiesMissing = function() {
      var isRequiredProperty = function(publicId) {
        return getPropertyMetadata(publicId).required;
      };
      var isChoicePropertyWithUnknownValue = function(property) {
        var propertyType = getPropertyMetadata(property.publicId).propertyType;
        return _.some((propertyType === "single_choice" || propertyType === "multiple_choice") && property.values, function(value) { return value.propertyValue == 99; });
      };

      return _.some(currentAsset.payload.properties, function(property) {
        return isRequiredProperty(property.publicId) && (
            isChoicePropertyWithUnknownValue(property) ||
            _.all(property.values, function(value) { return $.trim(value.propertyValue) === ""; })
          );
      });
    };

    var save = function() {
      if (currentAsset.id === undefined) {
        backend.createAsset(currentAsset.payload, function() {
          eventbus.trigger('asset:creationFailed');
          close();
        });
      } else {
        currentAsset.payload.id = currentAsset.id;
        var payload = payloadWithProperties(currentAsset.payload, changedProps);
        var positionUpdated = !_.isEmpty(_.intersection(changedProps, ['lon', 'lat']));
        backend.updateAsset(currentAsset.id, payload, function(asset) {
          changedProps = [];
          assetHasBeenModified = false;
          open(asset);
          eventbus.trigger('asset:saved', asset, positionUpdated);
        }, function() {
          backend.getMassTransitStopByNationalId(currentAsset.payload.nationalId, function(asset) {
            open(asset);
            eventbus.trigger('asset:updateFailed', asset);
          });
        });
      }
    };

    var switchDirection = function() {
      var validityDirection = validitydirections.switchDirection(get('validityDirection'));
      setProperty('vaikutussuunta', [{ propertyValue: validityDirection }]);
      currentAsset.payload.validityDirection = validityDirection;
    };

    var setProperty = function(publicId, values) {
      var propertyData = {publicId: publicId, values: values};
      changedProps = _.union(changedProps, [publicId]);
      currentAsset.payload.properties = updatePropertyData(currentAsset.payload.properties, propertyData);
      assetHasBeenModified = true;
      eventbus.trigger('assetPropertyValue:changed', { propertyData: propertyData, id: currentAsset.id });
    };

    var exists = function() {
      return !_.isEmpty(currentAsset);
    };

    var change = function(asset) {
      changeByNationalId(asset.nationalId);
    };

    var changeByNationalId = function(assetNationalId) {
      var anotherAssetIsSelectedAndHasNotBeenModified = exists() && currentAsset.payload.nationalId !== assetNationalId && !assetHasBeenModified;
      if (!exists() || anotherAssetIsSelectedAndHasNotBeenModified) {
        if (exists()) { close(); }
        backend.getMassTransitStopByNationalId(assetNationalId, function(asset) {
          eventbus.trigger('asset:fetched', asset);
        });
      }
    };

    var getId = function() {
      return currentAsset.id;
    };

    var getName = function() {
      return getPropertyValue({ propertyData: getProperties() }, 'nimi_suomeksi');
    };

    var getDirection = function() {
      return getPropertyValue({ propertyData: getProperties() }, 'liikennointisuuntima');
    };

    var get = function(key) {
      if (exists()) {
        return currentAsset.payload[key];
      }
    };

    function getPropertyValue(asset, propertyName) {
      return _.chain(asset.propertyData)
        .find(function (property) { return property.publicId === propertyName; })
        .pick('values')
        .values()
        .flatten()
        .map(extractDisplayValue)
        .value()
        .join(', ');
    }

    function extractDisplayValue(value) {
      if(_.has(value, 'propertyDisplayValue')) {
        return value.propertyDisplayValue;
      } else {
        return value.propertyValue;
      }
    }

    return {
      close: close,
      save: save,
      isDirty: function() { return assetHasBeenModified; },
      setProperty: setProperty,
      cancel: cancel,
      exists: exists,
      change: change,
      changeByExternalId: changeByNationalId,
      getId: getId,
      getName: getName,
      getDirection: getDirection,
      get: get,
      getProperties: getProperties,
      switchDirection: switchDirection,
      move: move,
      requiredPropertiesMissing: requiredPropertiesMissing,
      place: place,
      mixedVirtualAndRealStops:mixedVirtualAndRealStops
    };
  };

})(window.SelectedMassTransitStop = window.SelectedMassTransitStop || {});
