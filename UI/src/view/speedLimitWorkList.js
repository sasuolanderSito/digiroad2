(function (root) {
  root.SpeedLimitWorkList = function(){
    MunicipalityWorkList.call(this);
    var me = this;
    this.hrefDir = "#work-list/speedLimit/municipality";
    var municipalityList;
    var showFormBtnVisible = true;

    this.bindEvents = function () {
      eventbus.on('speedLimitMunicipality:select', function(listP, stateHistory) {
        $('.container').hide();
        $('#work-list').show();
        $('body').addClass('scrollable');
        municipalityList = listP;
        me.generateWorkList(listP, stateHistory);
      });
    };

    this.createVerificationForm = function(municipality) {
      $('#tableData').hide();
      $('.filter-box').hide();
      if (showFormBtnVisible)
        $('#work-list-header').append($('<a class="header-link"></a>').attr('href', me.hrefDir).html('Kuntavalinta').click(function(){
          me.generateWorkList(municipalityList);
        }));
      me.reloadForm(municipality);
    };

    this.municipalityTable = function (municipalities, filter) {
      var municipalityValues =
        _.isEmpty(filter) ? municipalities : _.filter(municipalities, function (municipality) {
          return municipality.name.toLowerCase().startsWith(filter.toLowerCase());
        });

      var tableContentRows = function (municipalities) {
        return _.map(municipalities, function (municipality) {
          return $('<tr/>').append($('<td/>').append(idLink(municipality)));
        });
      };
      var idLink = function (municipality) {
        return $('<a class="work-list-item"/>').attr('href', me.hrefDir).html(municipality.name).click(function(){
          me.createVerificationForm(municipality);
        });
      };
      return $('<table id="tableData"/>').append(tableContentRows(municipalityValues));
    };

    this.workListItemTable = function(layerName, workListItems, municipalityName) {
      var municipalityHeader = function(municipalityName, totalCount) {
        var countString = totalCount && layerName !== 'speedLimit' ? ' (yhteensä ' + totalCount + ' kpl)' : '';
        return $('<h2/>').html(municipalityName + countString);
      };
      var tableHeaderRow = function(headerName) {
        return $('<caption/>').html(headerName);
      };
      var tableContentRows = function(ids) {
        return _.map(ids, function(item, index) {
          return $('<tr/>').append($('<td/>').append(typeof item.id !== 'undefined' ? assetLink(item, index) : idLink(item, index)));
        });
      };
      var idLink = function(id, index) {
        var link = '#' + layerName + '/' + id;
        return $('<a class="work-list-item"/>').attr('href', link + '/' +municipalityName +'/'+ index).attr('id', index).html(link);
      };
      var floatingValidator = function() {
        return $('<span class="work-list-item"> &nbsp; *</span>');
      };
      var assetLink = function(asset, index) {
        var link = '#' + layerName + '/' + asset.id;
        var workListItem = $('<a class="work-list-item"/>').attr('href', link + '/' +municipalityName +'/'+ index).attr('id', index).html(link);
        if(asset.floatingReason === 1) //floating reason equal to RoadOwnerChanged
          workListItem.append(floatingValidator);
        return workListItem;
      };
      var tableForGroupingValues = function(values, ids, count) {
        if (!ids || ids.length === 0) return '';
        var countString = count ? ' (' + count + ' kpl)' : '';
        return $('<table/>').addClass('table')
          .append(tableHeaderRow(values + countString))
          .append(tableContentRows(ids));
      };

      return $('<div/>').append(municipalityHeader(municipalityName, workListItems.totalCount))
        .append(tableForGroupingValues('Kunnan omistama', workListItems.Municipality, workListItems.municipalityCount))
        .append(tableForGroupingValues('Valtion omistama', workListItems.State, workListItems.stateCount))
        .append(tableForGroupingValues('Yksityisen omistama', workListItems.Private, workListItems.privateCount))
        .append(tableForGroupingValues('Ei tiedossa', workListItems.Unknown, 0));
    };

    this.reloadForm = function(municipality){
      $('#formTable').remove();

      var unknownSpeedLimits = _.clone(municipality);
      delete unknownSpeedLimits.id;
      delete unknownSpeedLimits.name;

      var unknownLimits = _.map(unknownSpeedLimits, _.partial(me.workListItemTable, 'speedLimit'));
      $('#work-list .work-list').html(unknownLimits);
    };

    this.generateWorkList = function (listP, stateHistory) {
      var searchbox = $('<div class="filter-box">' +
        '<input type="text" class="location input-sm" placeholder="Kuntanimi" id="searchBox"></div>');

      var title = 'Tuntemattomien nopeusrajoitusten lista';
      $('#work-list').html('' +
        '<div style="overflow: auto;">' +
        '<div class="page">' +
        '<div class="content-box">' +
        '<header id="work-list-header">' + title +
        '<a class="header-link" href="#' + window.applicationModel.getSelectedLayer() + '">Sulje</a>' +
        '</header>' +
        '<div class="work-list">' +
        '</div>' +
        '</div>' +
        '</div>'
      );

      listP.then(function (limits) {
        var element = $('#work-list .work-list');
        if (limits.length == 1){
          showFormBtnVisible = false;
          me.createVerificationForm(_.first(limits));
        } else {
          if (stateHistory) {
            showFormBtnVisible = false;
            me.createVerificationForm(_.find(limits, function (limit) {
              return limit.name === stateHistory.municipality;
            }));
            $('#' + stateHistory.position).scrollView().focus();
          }
          else {
            var unknownLimits = _.partial.apply(null, [me.municipalityTable].concat([limits, ""]))();
            element.html($('<div class="municipality-list">').append(unknownLimits));

            if (_.contains(me.roles, 'operator') || _.contains(me.roles, 'premium'))
              searchbox.insertBefore('#tableData');

            $('#searchBox').on('keyup', function (event) {
              var currentInput = event.currentTarget.value;

              var unknownLimits = _.partial.apply(null, [me.municipalityTable].concat([limits, currentInput]))();
              $('#tableData tbody').html(unknownLimits);
            });
          }
        }
      });
    };

    $.fn.scrollView = function () {
      return this.each(function () {
        $('html, body').animate({
          scrollTop: $(this).offset().top
        }, 0);
      });
    };
  };
})(this);