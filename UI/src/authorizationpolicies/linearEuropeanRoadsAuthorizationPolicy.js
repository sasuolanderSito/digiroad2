(function(root) {
  root.LinearEuropeanRoadsAuthorizationPolicy = function() {
    AuthorizationPolicy.call(this);

    var me = this;

    this.formEditModeAccess = function(selectedAsset) {
      var isMaintainerAndHaveRights = (me.isMunicipalityMaintainer() || me.isElyMaintainer()) && me.hasRightsInMunicipality(selectedAsset.municipalityCode);
      if(me.isOperator()){
        return true;
      }else{
        return me.isStateExclusions(selectedAsset) || (!me.isState(selectedAsset) && isMaintainerAndHaveRights );
      }
    };

    this.handleSuggestedAsset = function(selectedAsset, value, layerMode) {
      if (layerMode === 'readOnly')
        return !!parseInt(value);
      else
        return !selectedAsset.isSplitOrSeparated() && (_.isNull((selectedAsset.getId()) && me.isOperator()) || (!!parseInt(value) && (me.isOperator() || me.isMunicipalityMaintainer())));
    };
  };
})(this);