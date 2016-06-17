(function (root) {
  root.ManoeuvreForm = function(selectedManoeuvreSource) {

    /*
    * HTML Templates
    */
    var saveAndCancelButtons = '' +
      '<div class="manoeuvres form-controls">' +
        '<button class="save btn btn-primary" disabled>Tallenna</button>' +
        '<button class="cancel btn btn-secondary" disabled>Peruuta</button>' +
      '</div>';

    var newIntermediateTemplate = '' +
        '<div class="target-link-selection">' +
        '<input type="hidden" class="old-link-ids" value="<%= oldLinkIds %>">' +
        '<ul>' + '' +
        '<li><input type="radio" name="target" value="0" checked> Viimeinen linkki</input></li>' +
        '<% _.forEach(adjacentLinks, function(l) { %>' +
        '<li><input type="radio" name="target" value="<%=l.linkId%>"> LINK ID <%= l.linkId %> ' +
        '</input>' +
        '<span class="marker"><%= l.marker %></span></li>' +
        ' <% }) %>' +
        '</ul>' +
        '</div>';

    var templateWithHeaderAndFooter = '' +
      '<header>' +
        '<span>Linkin LINK ID: <%= linkId %></span>' +
        saveAndCancelButtons +
      '</header>' +
      '<div class="wrapper read-only">' +
        '<div class="form form-horizontal form-dark form-manoeuvre">' +
          '<div class="form-group">' +
            '<p class="form-control-static asset-log-info">Muokattu viimeksi: <%- modifiedBy %> <%- modifiedAt %> </p>' +
          '</div>' +
          '<label>Kääntyminen kielletty linkeille</label>' +
          '<div></div>' +
        '</div>' +
      '</div>' +
      '<footer>' + saveAndCancelButtons + '</footer>';

    var manouvresViewModeTemplate = '' +
      '<div class="form-group manoeuvre">' +
        '<p class="form-control-static">LINK ID: <%= destLinkId %>' +
          "<%print(isLinkChain ? '<span title=\"Kielletty välilinkin tai -linkkien kautta\" class=\"marker\">✚</span> ' : '')%>" +
        ' </p>' +
        '<% if(localizedExceptions.length > 0) { %>' +
          '<div class="form-group exception-group">' +
            '<label>Rajoitus ei koske seuraavia ajoneuvoja</label>' +
            '<ul>' +
              '<% _.forEach(localizedExceptions, function(e) { %> <li><%- e.title %></li> <% }) %>' +
            '</ul>' +
          '</div>' +
        '<% } %>' +
        '<% if(validityPeriods.length > 0) { %>' +
          '<div class="form-group validity-period-group">' +
            '<label>Rajoituksen voimassaoloaika (lisäkilvessä):</label>' +
            '<ul>' +
              '<%= validityPeriodElements %>' +
            '</ul>' +
          '</div>' +
        '<% } %>' +
        '<% if(!_.isEmpty(additionalInfo)) { %> <label>Tarkenne: <%- additionalInfo %></label> <% } %>' +
      '</div>';

    var adjacentLinkTemplate = '' +
      '<div class="form-group adjacent-link" manoeuvreId="<%= manoeuvreId %>" linkId="<%= linkId %>" style="display: none">' +
        '<div class="form-group">' +
          '<p class="form-control-static">LINK ID <%= linkId %> ' +
          '<span class="marker"><%= marker %></span>' +
          '<span class="edit-buttons">'+renderEditButtons()+'</span></p>' +
        '</div>' +
        '<div class="manoeuvre-details-edit-mode">' +
        '<% if(localizedExceptions.length > 0) { %>' +
        '<div class="form-group exception-group">' +
        '<label>Rajoitus ei koske seuraavia ajoneuvoja</label>' +
        '<ul>' +
        '<% _.forEach(localizedExceptions, function(e) { %> <li><%- e.title %></li> <% }) %>' +
        '</ul>' +
        '</div>' +
        '<% } %>' +
        '<% if(validityPeriodElements.length > 0) { %>' +
        '<div class="form-group validity-period-group">' +
        '<label>Rajoituksen voimassaoloaika (lisäkilvessä):</label>' +
        '<ul>' +
        '<%= validityPeriodElements %>' +
        '</ul>' +
        '</div>' +
        '<% } %>' +
        '<% if(!_.isEmpty(additionalInfo)) { %> <label>Tarkenne: <%- additionalInfo %></label> <% } %>' +
        '<% if( (_.isEmpty(additionalInfo)) && (localizedExceptions.length == 0) && (validityPeriodElements.length == 0) ) { %> <label>Ei rajoitusta</label> <% } %>' +
        '</div>' +
        '<div class="manoeuvre-details" hidden>' +
          '<div class="validity-period-group">' +
          ' <label>Rajoituksen voimassaoloaika (lisäkilvessä):</label>' +
          ' <ul>' +
          '   <%= existingValidityPeriodElements %>' +
              newValidityPeriodElement() +
          ' </ul>' +
          '</div>' +
          '<div>' +
            '<label>Rajoitus ei koske seuraavia ajoneuvoja</label>' +
            '<% _.forEach(localizedExceptions, function(selectedException) { %>' +
              '<div class="form-group exception">' +
                '<%= deleteButtonTemplate %>' +
                '<select class="form-control select">' +
                  '<% _.forEach(exceptionOptions, function(exception) { %> ' +
                    '<option value="<%- exception.typeId %>" <% if(selectedException.typeId === exception.typeId) { print(selected="selected")} %> ><%- exception.title %></option> ' +
                  '<% }) %>' +
                '</select>' +
              '</div>' +
            '<% }) %>' +
            '<%= newExceptionSelect %>' +
            '<div class="form-group">' +
              '<input type="text" class="form-control additional-info" ' +
                                 'placeholder="Muu tarkenne" <% print(manoeuvreExists ? "" : "disabled") %> ' +
                                 '<% if(additionalInfo) { %> value="<%- additionalInfo %>" <% } %>/>' +
            '</div>' +
            '<div class="form-remove">' +
              '<div class="checkbox" >' +
                '<input type="checkbox"/>' +
              '</div>' +
              '<p class="form-control-static">Poista</p>' +
            '</div>' +
            '<div class="form-group continue-option-group" manoeuvreId="<%= manoeuvreId %>" linkId="<%= linkId %>">' +
              '<label>Jatka kääntymisrajoitusta</label>' +
                newIntermediateTemplate +
            '</div>' +
          '<div>' +
        '<div>' +
      '</div>';

    var targetLinkTemplate = '' +
      '<div class="form-group adjacent-link" manoeuvreId="<%= manoeuvreId %>" linkId="<%= linkId %>" style="display: none">' +
      '<div class="form-group">' +
      '<p class="form-control-static">LINK ID <%= linkId %> ' +
      "<%print(isLinkChain ? '<span title=\"Kielletty välilinkin tai -linkkien kautta\" class=\"marker\">✚</span> ' : '')%>" +
      '<span class="marker"><%= marker %></span>' +
      '<span class="edit-buttons">'+renderEditButtons()+'</span></p>' +
      '</div>' +
      '<div class="manoeuvre-details-edit-mode">' +
        '<% if(localizedExceptions.length > 0) { %>' +
        '<div class="form-group exception-group">' +
          '<label>Rajoitus ei koske seuraavia ajoneuvoja</label>' +
          '<ul>' +
          '<% _.forEach(localizedExceptions, function(e) { %> <li><%- e.title %></li> <% }) %>' +
          '</ul>' +
        '</div>' +
        '<% } %>' +
        '<% if(existingValidityPeriodElements.length > 0) { %>' +
        '<div class="form-group validity-period-group">' +
          '<label>Rajoituksen voimassaoloaika (lisäkilvessä):</label>' +
          '<ul>' +
          '<%= existingValidityPeriodElements %>' +
          '</ul>' +
        '</div>' +
        '<% } %>' +
        '<% if(!_.isEmpty(additionalInfo)) { %> <label>Tarkenne: <%- additionalInfo %></label> <% } %>' +
      '</div>' +
      '<div class="manoeuvre-details" hidden>' +
      '<div class="validity-period-group">' +
      ' <label>Rajoituksen voimassaoloaika (lisäkilvessä):</label>' +
      ' <ul>' +
      '   <%= existingValidityPeriodElements %>' +
      newValidityPeriodElement() +
      ' </ul>' +
      '</div>' +
      '<div>' +
      '<label>Rajoitus ei koske seuraavia ajoneuvoja</label>' +
      '<% _.forEach(localizedExceptions, function(selectedException) { %>' +
      '<div class="form-group exception">' +
      '<%= deleteButtonTemplate %>' +
      '<select class="form-control select">' +
      '<% _.forEach(exceptionOptions, function(exception) { %> ' +
      '<option value="<%- exception.typeId %>" <% if(selectedException.typeId === exception.typeId) { print(selected="selected")} %> ><%- exception.title %></option> ' +
      '<% }) %>' +
      '</select>' +
      '</div>' +
      '<% }) %>' +
      '<%= newExceptionSelect %>' +
      '<div class="form-group">' +
      '<input type="text" class="form-control additional-info" ' +
      'placeholder="Muu tarkenne" <% print(manoeuvreExists ? "" : "disabled") %> ' +
      '<% if(additionalInfo) { %> value="<%- additionalInfo %>" <% } %>/>' +
      '</div>' +
      '<div class="form-remove">' +
        '<div class="checkbox" >' +
          '<input type="checkbox"/>' +
        '</div>' +
        '<p class="form-control-static">Poista</p>' +
      '</div>' +
        '<div class="form-group continue-option-group" manoeuvreId="<%= manoeuvreId %>" linkId="<%= linkId %>" hidden>' +
          '<label>Jatka kääntymisrajoitusta</label>' +
            newIntermediateTemplate +
        '</div>' +
      '<div>' +
      '<div>' +
      '</div>';

    var newExceptionTemplate = '' +
      '<div class="form-group exception">' +
        '<select class="form-control select new-exception" <% print(manoeuvreExists ? "" : "disabled") %> >' +
          '<option class="empty" disabled selected>Valitse tyyppi</option>' +
          '<% _.forEach(exceptionOptions, function(exception) { %> <option value="<%- exception.typeId %>"><%- exception.title %></option> <% }) %>' +
        '</select>' +
      '</div>';

    var deleteButtonTemplate = '<button class="btn-delete delete">x</button>';

    /**
     * Bind events and HTML templates
     */
    var bindEvents = function() {
      // Get form root element into variable by div id 'feature-attributes'
      var rootElement = $('#feature-attributes');

      // Show and hide form elements according to readOnly value
      function toggleMode(readOnly) {
        rootElement.find('.adjacent-link').toggle(!readOnly);
        rootElement.find('.manoeuvre').toggle(readOnly);
        rootElement.find('.form-controls').toggle(!readOnly);
        if(readOnly){
          rootElement.find('.wrapper').addClass('read-only');
        } else {
          rootElement.find('.wrapper').removeClass('read-only');
        }
      }

      // Listen to view/edit mode button
      eventbus.on('application:readOnly', toggleMode);

      // Listen to road link selection on map
      eventbus.on('manoeuvres:selected manoeuvres:cancelled', function(roadLink) {
        roadLink.modifiedBy = roadLink.modifiedBy || '-';
        roadLink.modifiedAt = roadLink.modifiedAt || '';
        rootElement.html(_.template(templateWithHeaderAndFooter)(roadLink));

        // Create html elements for view mode
        _.each(roadLink.manoeuvres, function (manoeuvre) {
          // Verify if Manoeuvre have intermediate Links to show the plus sign
          var isLinkChain = manoeuvre.intermediateLinkIds.length > 0 ? true : false;
          var localizedExceptions = localizeExceptions(manoeuvre.exceptions);
          var validityPeriodElements = _(manoeuvre.validityPeriods)
              .sortByAll(dayOrder, 'startHour', 'endHour')
              .map(validityPeriodDisplayElement)
              .join('');

          rootElement.find('.form').append(_.template(manouvresViewModeTemplate)(_.merge({}, manoeuvre, {
            isLinkChain: isLinkChain,
            localizedExceptions: localizedExceptions,
            validityPeriodElements: validityPeriodElements
          })));
        });

        // Create html elements for edit mode
        _.each(roadLink.adjacent, function(adjacentLink) {
          var manoeuvre = _.find(roadLink.manoeuvres, function(manoeuvre) { return adjacentLink.linkId === manoeuvre.destLinkId; });
          var manoeuvreExists = manoeuvre ? true : false;
          var manoeuvreId = manoeuvre ? manoeuvre.id.toString(10) : "";
          var localizedExceptions = manoeuvre ? localizeExceptions(manoeuvre.exceptions) : '';
          var additionalInfo = (manoeuvre && !_.isEmpty(manoeuvre.additionalInfo)) ? manoeuvre.additionalInfo : null;
          var existingValidityPeriodElements =
            manoeuvre ?
              _(manoeuvre.validityPeriods)
                .sortByAll(dayOrder, 'startHour', 'endHour')
                .map(validityPeriodElement)
                .join('') :
              '';
          var isLinkChain = _.some(roadLink.manoeuvres, function (manoeuvre) {
            return _.some(manoeuvre.intermediateLinkIds, function (intermediateLinkId) {
              return intermediateLinkId == adjacentLink.linkId;
            });
          });
          var newExceptionSelect = _.template(newExceptionTemplate)({ exceptionOptions: exceptionOptions(), manoeuvreExists: manoeuvreExists });
          var validityPeriodElements = manoeuvre ? _(manoeuvre.validityPeriods)
              .sortByAll(dayOrder, 'startHour', 'endHour')
              .map(validityPeriodDisplayElement)
              .join('') :
              '';

          rootElement.find('.form').append(_.template(adjacentLinkTemplate)(_.merge({}, adjacentLink, {
            manoeuvreExists: manoeuvreExists,
            manoeuvreId: manoeuvreId,
            exceptionOptions: exceptionOptions(),
            localizedExceptions: localizedExceptions,
            additionalInfo: additionalInfo,
            newExceptionSelect: newExceptionSelect,
            deleteButtonTemplate: deleteButtonTemplate,
            existingValidityPeriodElements: existingValidityPeriodElements,
            isLinkChain: isLinkChain,
            validityPeriodElements: validityPeriodElements,
            oldLinkIds: ''
          })));
        });

        _.each(roadLink.nonAdjacentTargets, function(target) {
          var manoeuvre = _.find(roadLink.manoeuvres, function(manoeuvre) { return target.linkId === manoeuvre.destLinkId; });
          var manoeuvreExists = true;
          var manoeuvreId = manoeuvre.id ? manoeuvre.id.toString(10) : "";
          var localizedExceptions = localizeExceptions(manoeuvre.exceptions);
          var additionalInfo = (!_.isEmpty(manoeuvre.additionalInfo)) ? manoeuvre.additionalInfo : null;
          var existingValidityPeriodElements =
              _(manoeuvre.validityPeriods)
                  .sortByAll(dayOrder, 'startHour', 'endHour')
                  .map(validityPeriodElement)
                  .join('');
          // Verify if Manoeuvre have intermediate Links to show the plus sign
          var isLinkChain = true;
          rootElement.find('.form').append(_.template(targetLinkTemplate)(_.merge({}, target, {
            linkId: manoeuvre.destLinkId,
            manoeuvreExists: manoeuvreExists,
            manoeuvreId: manoeuvreId,
            exceptionOptions: exceptionOptions(),
            localizedExceptions: localizedExceptions,
            additionalInfo: additionalInfo,
            newExceptionSelect: _.template(newExceptionTemplate)({ exceptionOptions: exceptionOptions(), manoeuvreExists: manoeuvreExists }),
            deleteButtonTemplate: deleteButtonTemplate,
            existingValidityPeriodElements: existingValidityPeriodElements,
            isLinkChain: isLinkChain,
            oldLinkIds: ''
          })));
        });

        toggleMode(applicationModel.isReadOnly());

        var manoeuvreData = function(formGroupElement) {
          /*
          WIP: Possible new version of manoeuvreData
           var manoeuvreId = !_.isEmpty(formGroupElement.attr('manoeuvreId')) ? parseInt(formGroupElement.attr('manoeuvreId'), 10) : null;
           var destLinkId = null;
           var linkIds = null;
           var firstTargetLinkId = null;
           var additionalInfo = !_.isEmpty(formGroupElement.find('.additional-info').val()) ? formGroupElement.find('.additional-info').val() : null;
           var oldLinkIds = !_.isEmpty(formGroupElement.find('.old-link-ids').val()) ? formGroupElement.find('.old-link-ids').val() : null;
           var linkIds = null;
           var nextTargetLinkId = null;

           if (oldLinkIds === null) {
           firstTargetLinkId = parseInt(formGroupElement.attr('linkId'), 10);
           nextTargetLinkId = parseInt(formGroupElement.find('input:radio[name="target"]:checked').val(), 10);
           linkIds = [firstTargetLinkId];  // source link and target link are added later to linkIds List
           } else {
           linkIds = oldLinkIds.split(",").map(Number);
           console.log(linkIds);
           firstTargetLinkId = linkIds[0];
           nextTargetLinkId = parseInt(formGroupElement.find('input:radio[name="target"]:checked').val(), 10);
           }
           */
          var firstTargetLinkId = parseInt(formGroupElement.attr('linkId'), 10);
          var destLinkId = firstTargetLinkId;
          var manoeuvreId = !_.isEmpty(formGroupElement.attr('manoeuvreId')) ? parseInt(formGroupElement.attr('manoeuvreId'), 10) : null;
          var additionalInfo = !_.isEmpty(formGroupElement.find('.additional-info').val()) ? formGroupElement.find('.additional-info').val() : null;
          // TODO: Works only for three link manoeuvre. Need to store previously checked intermediate link ids hidden somewhere in the form?
          //var linkChainId = parseInt(formGroupElement.find('input:radio[name="target"]:checked').val());
          //var linkIds = [firstTargetLinkId];
          //if (linkChainId && linkChainId !== 0) {
          //  linkIds.push(linkChainId);
          //}
          var nextTargetLinkId = parseInt(formGroupElement.find('input:radio[name="target"]:checked').val(), 10);
          var linkIds = [firstTargetLinkId];
          return {
            manoeuvreId: manoeuvreId,
            firstTargetLinkId: firstTargetLinkId,
            nextTargetLinkId: nextTargetLinkId,
            destLinkId: destLinkId,
            linkIds: linkIds,
            exceptions: manoeuvreExceptions(formGroupElement),
            validityPeriods: manoeuvreValidityPeriods(formGroupElement),
            additionalInfo: additionalInfo
          };
        };

        function manoeuvreValidityPeriods(element) {
          var periodElements = element.find('.existing-validity-period');
          return _.map(periodElements, function (element) {
            return {
              startHour: parseInt($(element).find('.start-hour').val(), 10),
              endHour: parseInt($(element).find('.end-hour').val(), 10),
              days: $(element).data('days')
            };
          });
        }

        var manoeuvreExceptions = function(formGroupElement) {
          var selectedOptions = formGroupElement.find('.exception select option:selected');
          return _.chain(selectedOptions)
            .map(function(option) { return parseInt($(option).val(), 10); })
            .reject(function(val) { return _.isNaN(val); })
            .value();
        };

        function updateValidityPeriods(element) {
          var manoeuvre = manoeuvreData(element);
          var manoeuvreId = manoeuvre.manoeuvreId;
          if (_.isNull(manoeuvreId)) {
            selectedManoeuvreSource.addManoeuvre(manoeuvre);
          } else {
            selectedManoeuvreSource.setValidityPeriods(manoeuvreId, manoeuvre.validityPeriods);
          }
        }

        var throttledAdditionalInfoHandler = _.throttle(function(event) {
          var manoeuvre = manoeuvreData($(event.delegateTarget));
          var manoeuvreId = manoeuvre.manoeuvreId;
          if (_.isNull(manoeuvreId)) {
            selectedManoeuvreSource.addManoeuvre(manoeuvre);
          } else {
            selectedManoeuvreSource.setAdditionalInfo(manoeuvreId, manoeuvre.additionalInfo || "");
          }
        }, 1000);

        rootElement.find('.adjacent-link').on('input', 'input[type="text"]', throttledAdditionalInfoHandler);

        // Verify value of checkBox for remove the manoeuvres
        // If the checkBox was checked remove the manoeuvre
        rootElement.find('.adjacent-link').on('change', 'input[type="checkbox"]', function(event) {
          var eventTarget = $(event.currentTarget);
          var manoeuvreToEliminate = manoeuvreData($(event.delegateTarget));
          if (eventTarget.attr('checked') === 'checked') {
            selectedManoeuvreSource.removeManoeuvre(manoeuvreToEliminate);
            // TODO: disable other form elements?
          }
        });

        // Listen to 'new manoeuvre' button click
        rootElement.find('.adjacent-link').on('click', '.edit button.new', function(event){
          var formGroupElement = $(event.delegateTarget);

          // Hide other adjacent links and their markers
          formGroupElement.siblings('.adjacent-link').remove();
          formGroupElement.find('.form-control-static .marker').remove();

          // Show select menus (validity period and exceptions)
          var selects = formGroupElement.find('select');
          selects.prop('disabled', false);

          // Show additional info textbox
          var text = formGroupElement.find('input[type="text"]');
          text.prop('disabled', false);

          // Slide down manoeuvre details part
          var group = formGroupElement.find('.manoeuvre-details');
          group.slideDown('fast');

          // Hide new/modify buttons
          var editButton = formGroupElement.find('.edit');
          editButton.prop('hidden',true);

          // Hide CheckBox to remove manoeuvre
          var checkBoxHide = formGroupElement.find('.form-remove');
          checkBoxHide.prop('hidden', true);

          // Hide manoeuvre data under the first link
          var manoeuvreDataUnderLink = formGroupElement.find('.manoeuvre-details-edit-mode');
          manoeuvreDataUnderLink.prop('hidden', true);

          var manoeuvre = manoeuvreData(formGroupElement);
          selectedManoeuvreSource.addManoeuvre(manoeuvre);

          var target = selectedManoeuvreSource.get().adjacent.find(function (rl) {
            return rl.linkId == manoeuvre.destLinkId;
          });
          if (!target) {
            target = selectedManoeuvreSource.get().nonAdjacentTargets.find(function (rl) {
              return rl.linkId == manoeuvre.destLinkId;
            });
          }
          selectedManoeuvreSource.setDirty(true);
          eventbus.trigger('manoeuvre:showExtension', target);
        });

        // Listen to 'modify manoeuvre' button click
        rootElement.find('.adjacent-link').on('click', '.edit button.modify', function(event){
          var formGroupElement = $(event.delegateTarget);

          // Hide other adjacent links and their markers
          formGroupElement.siblings('.adjacent-link').remove();
          formGroupElement.find('.form-control-static .marker').remove();

          // Show select menus (validity period and exceptions)
          var selects = formGroupElement.find('select');
          selects.prop('disabled', false);

          // Show additional info textbox
          var text = formGroupElement.find('input[type="text"]');
          text.prop('disabled', false);

          // Slide down manoeuvre details part
          var group = formGroupElement.find('.manoeuvre-details');
          group.slideDown('fast');

          // Hide new/modify buttons
          var editButton = formGroupElement.find('.edit');
          editButton.prop('hidden',true);

          // Hide continue link chain button
          var continueButton = formGroupElement.find('.continue');
          continueButton.prop('hidden', true);

          // Hide continue link chain notification
          var notification = formGroupElement.find('.form-notification');
          notification.prop('hidden', true);

          // Hide manoeuvre data under the first link
          var manoeuvreDataUnderLink = formGroupElement.find('.manoeuvre-details-edit-mode');
          manoeuvreDataUnderLink.prop('hidden', true);

          //var manoeuvre = manoeuvreData(formGroupElement);
          //selectedManoeuvreSource.addManoeuvre(manoeuvre);

          selectedManoeuvreSource.setDirty(true);
        });

        // Listen to 'continue manoeuvre' button click
        rootElement.find('.adjacent-link').on('click', '.continue button.continue', function(event){
          var formGroupElement = $(event.delegateTarget);

          var notification = formGroupElement.find('.form-notification');
          var continueButton = formGroupElement.find('.continue');
          var optionsGroup = formGroupElement.find('.continue-option-group');

          var manoeuvre = manoeuvreData(formGroupElement);
          var target = selectedManoeuvreSource.get().adjacent.find(function (rl) {
            return rl.linkId == manoeuvre.destLinkId;
          });
          if (!target) {
            target = selectedManoeuvreSource.get().nonAdjacentTargets.find(function (rl) {
              return rl.linkId == manoeuvre.destLinkId;
            });
          }
          eventbus.trigger('manoeuvre:showExtension', target);

          notification.prop('hidden', true);
          continueButton.prop('hidden',true);
          optionsGroup.prop('hidden',false);
        });

        // Listen to link chain radio button click
        rootElement.find('.continue-option-group').on('click', 'input:radio[name="target"]', function(event) {
          var formGroupElement = $(event.delegateTarget);
          var targetLinkId = Number(formGroupElement.attr('linkId'));
          var checkedLinkId = parseInt(formGroupElement.find(':checked').val(), 10);
          var manoeuvre = manoeuvreData(formGroupElement);

          if (targetLinkId && checkedLinkId) {
            eventbus.trigger('manoeuvre:extend', {target: targetLinkId, newTargetId: checkedLinkId, manoeuvre: manoeuvre});
          }

        });

        rootElement.find('.adjacent-link').on('change', '.exception .select', function(event) {
          var manoeuvre = manoeuvreData($(event.delegateTarget));
          var manoeuvreId = manoeuvre.manoeuvreId;
          if (_.isNull(manoeuvreId)) {
            selectedManoeuvreSource.addManoeuvre(manoeuvre);
          } else {
            selectedManoeuvreSource.setExceptions(manoeuvreId, manoeuvre.exceptions);
          }
        });

        rootElement.find('.adjacent-link').on('change', '.existing-validity-period .select', function(event) {
          updateValidityPeriods($(event.delegateTarget));
        });

        rootElement.find('.adjacent-link').on('change', '.new-exception', function(event) {
          var selectElement = $(event.target);
          var formGroupElement = $(event.delegateTarget);
          selectElement.parent().after(_.template(newExceptionTemplate)({
            exceptionOptions: exceptionOptions(),
            manoeuvreExists: true
          }));
          selectElement.removeClass('new-exception');
          selectElement.find('option.empty').remove();
          selectElement.before(deleteButtonTemplate);
          selectElement.parent().on('click', 'button.delete', function(event) {
            deleteException($(event.target).parent(), formGroupElement);
          });
        });

        rootElement.find('.adjacent-link').on('change', '.new-validity-period select', function(event) {
          $(event.target).closest('.validity-period-group ul').append(newValidityPeriodElement());
          $(event.target).parent().parent().replaceWith(validityPeriodElement({
            days: $(event.target).val(),
            startHour: 0,
            endHour: 24
          }));
          updateValidityPeriods($(event.delegateTarget));
        });

        rootElement.find('.adjacent-link').on('click', '.exception button.delete', function(event) {
          deleteException($(event.target).parent(), $(event.delegateTarget));
        });

        rootElement.find('.adjacent-link').on('click', '.existing-validity-period .delete', function(event) {
          $(event.target).parent().parent().remove();
          updateValidityPeriods($(event.delegateTarget));
        });

        var deleteException = function(exceptionRow, formGroupElement) {
          exceptionRow.remove();
          var manoeuvre = manoeuvreData(formGroupElement);
          if (_.isNull(manoeuvre.manoeuvreId)) {
            selectedManoeuvreSource.addManoeuvre(manoeuvre);
          } else {
            selectedManoeuvreSource.setExceptions(manoeuvre.manoeuvreId, manoeuvre.exceptions);
          }
        };
      });

      eventbus.on('manoeuvres:unselected', function() {
        rootElement.empty();
      });

      eventbus.on('manoeuvres:saved', function() {
        rootElement.find('.form-controls button').attr('disabled', true);
      });

      eventbus.on('manoeuvre:changed', function() {
        rootElement.find('.form-controls button').attr('disabled', false);
      });

      eventbus.on('manoeuvre:linkAdded', function(manoeuvre) {
        console.log("manoeuvre:linkAdded, data = ");
        console.log(manoeuvre);
        var element = rootElement.find('.continue-option-group');
        var link = selectedManoeuvreSource.get();
        rootElement.find('.adjacent-link').find('.form-control-static').text("LINK ID " + manoeuvre.destLinkId);
        element.attr('linkid', manoeuvre.destLinkId);
        element.find(".target-link-selection").replaceWith(_.template(newIntermediateTemplate)(_.merge({}, { "adjacentLinks": manoeuvre.adjacentLinks,  "oldLinkIds": manoeuvre.linkIds.join()  } )));

        // TODO Work in progress
        element.find('input:radio[name="target"]').on('click', function(event) {
          var formGroupElement = $(event.delegateTarget);
          var targetLinkId = Number(formGroupElement.attr('linkId'));
          var checkedLinkId = parseInt(formGroupElement.find(':checked').val(), 10);

          if (targetLinkId && checkedLinkId) {
            eventbus.trigger('manoeuvre:extend', {target: targetLinkId, newTargetId: checkedLinkId, manoeuvre: manoeuvre});
          }

        });
      });

      rootElement.on('click', '.manoeuvres button.save', function() {
        selectedManoeuvreSource.save();
      });

      rootElement.on('click', '.manoeuvres button.cancel', function() {
        selectedManoeuvreSource.cancel();
      });
    };

    bindEvents();
  };

  /*
   * Utility functions
   */

  // TODO: Remove this function if not needed. Content is the same as in newIntermediateTemplate
  // Generate radio buttons for next possible linkIds when making chain for nonAdjacentTargets and Adjacent
  function linkChainRadioButtons(adjacentLinks) {
    return '' +
      '<ul>' +
      '<li><input type="radio" name="target" value="0" checked> Viimeinen linkki</input></li>' +
      '<% _.forEach(adjacentLinks, function(l) { %>' +
      '<li><input type="radio" name="target" value="<%=l.linkId%>"> LINK ID <%= l.linkId %> ' +
      '</input>' +
      '<span class="marker"><%= l.marker %></span></li>' +
      ' <% }) %>' +
      '</ul>';
  }

  function newValidityPeriodElement() {
    return '' +
        '<li><div class="form-group new-validity-period">' +
        '  <select class="form-control select">' +
        '    <option class="empty" disabled selected>Lisää voimassaoloaika</option>' +
        '    <option value="Weekday">Ma–Pe</option>' +
        '    <option value="Saturday">La</option>' +
        '    <option value="Sunday">Su</option>' +
        '  </select>' +
        '</div></li>';
  }
  function renderEditButtons(){
    return '' +
        '<div class="edit buttons group">' +
        '<div <% print(manoeuvreExists ? "hidden" : "") %>>'+
        '<button class="new btn btn-new">Uusi rajoitus</button>' +
        '</div>'+
        '<div <% print(manoeuvreExists ? "" : "hidden") %>>'+
        '<button class="modify btn btn-modify">Muokkaa</button>' +
        '</div>'+
        '</div>';
  }

  var dayLabels = {
    Weekday: "Ma–Pe",
    Saturday: "La",
    Sunday: "Su"
  };
  function validityPeriodElement(period) {
    return '' +
      '<li><div class="form-group existing-validity-period" data-days="' + period.days + '">' +
      '  <button class="delete btn-delete">x</button>' +
      '  <label class="control-label">' +
           dayLabels[period.days] +
      '  </label>' +
         hourElement(period.startHour, 'start') +
      '  <span class="hour-separator"> - </span>' +
         hourElement(period.endHour, 'end') +
      '</div></li>';
  }

  function validityPeriodDisplayElement(period) {
    return '' +
      '<li><div class="form-group existing-validity-period" data-days="' + period.days + '">' +
        dayLabels[period.days] + ' ' + period.startHour + '–' + period.endHour +
      '</div></li>';
  }

  function hourElement(selectedHour, type) {
    var className = type + '-hour';
    return '' +
      '<select class="form-control sub-control select ' + className + '">' +
      hourOptions(selectedHour, type) +
      '</select>';
  }

  function hourOptions(selectedOption, type) {
    var range = type === 'start' ? _.range(0, 24) : _.range(1, 25);
    return _.map(range, function (hour) {
      var selected = hour === selectedOption ? 'selected' : '';
      return '<option value="' + hour + '" ' + selected + '>' + hour + '</option>';
    }).join('');
  }

  function localizeExceptions(exceptions) {
    var exceptionTypes = exceptionOptions();

    return _(exceptions)
      .map(function(typeId) {
        return _.find(exceptionTypes, {typeId: typeId});
      })
      .filter()
      .value();
  }

  function exceptionOptions() {
    return [
      {typeId: 21, title: 'Huoltoajo'},
      {typeId: 22, title: 'Tontille ajo'},
      {typeId: 10, title: 'Mopo'},
      {typeId: 9, title: 'Moottoripyörä'},
      {typeId: 27, title: 'Moottorikelkka'},
      {typeId: 5, title: 'Linja-auto'},
      {typeId: 8, title: 'Taksi'},
      {typeId: 7, title: 'Henkilöauto'},
      {typeId: 6, title: 'Pakettiauto'},
      {typeId: 4, title: 'Kuorma-auto'},
      {typeId: 15, title: 'Matkailuajoneuvo'},
      {typeId: 19, title: 'Sotilasajoneuvo'},
      {typeId: 13, title: 'Ajoneuvoyhdistelmä'},
      {typeId: 14, title: 'Traktori tai maatalousajoneuvo'}
    ];
  }

  function dayOrder(period) {
    var days = {
      Weekday: 0,
      Saturday: 1,
      Sunday: 2
    };
    return days[period.days];
  }
})(this);
