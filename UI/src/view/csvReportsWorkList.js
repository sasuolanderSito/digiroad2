(function (root) {
  root.CsvReportsWorkList = function () {
    WorkListView.call(this);
    var me = this;
    this.hrefDir = "#work-list/csvReports";
    this.title = 'Raportointityökalu';
    var backend;
    var municipalities;
    var assetsList;
    var refresh;


    this.initialize = function (mapBackend) {
      backend = mapBackend;
      me.bindEvents();
    };


    this.bindEvents = function () {
      eventbus.on('csvReports:select', function (listP) {
        $('.container').hide();
        $('#work-list').show();
        $('body').addClass('scrollable');
        me.generateWorkList(listP);
      });

    };

    function getMunicipalities() {
      if (_.isEmpty($("#municipalities_search").find('option'))) {
        if (_.isEmpty(municipalities)) {

          backend.getMunicipalities().then(function (result) {
            municipalities = result;
            setMunicipalities();
          });

        } else
          setMunicipalities();
      }
    }

    function getAssetsType() {
      if (_.isEmpty($("#assets_search").find('option'))) {
        if (_.isEmpty(assetsList)) {

          backend.getAssetTypes().then(function (result) {
            assetsList = result;
            setAssets();
          });
        } else
          setAssets();
      }
    }

    function setMunicipalities() {
      $('#municipalities_search').append($('<option>', {
        value: "1000",
        text: "Kaikki kunnat"  /* all municipalities */
      }));

      _.forEach(municipalities, function (municipality) {
        $('#municipalities_search').append($('<option>', {
          value: municipality.id,
          text: municipality.name
        }));
      });
    }

    function setAssets() {
      var topOptions = [
        {"id": "1", "value": "Kaikki tietolajit"}, /* all assets */
        {"id": "2", "value": "Pistemäiset tietolajit"}, /* point assets */
        {"id": "3", "value": "Viivamaiset tietolajit"} /* linear assets */
      ];

      _.forEach(topOptions, function (option) {
        $('#assets_search').append($('<option>', {
          value: option.id,
          text: option.value
        }));
      });

      _.forEach(assetsList, function (asset) {
        $('#assets_search').append($('<option>', {
          value: asset.id,
          text: asset.name
        }));
      });
    }


    function populatedMultiSelectBoxes() {
      getMunicipalities();
      getAssetsType();
    }

    function municipalitySort(a, b){
      /* value: "1000", text: "Kaikki kunnat"  /* all municipalities */

      if (a.value == "1000")
        return -1;
      else if ( b.value == "1000")
        return 1;

      return a.text > b.text ? 1 : -1;
    }

    function assetTypeSort(a, b){
      /* value: "1", text: "Kaikki tietolajit"}, /* all assets */
      /* value: "2", text: "Pistemäiset tietolajit"}, /* point assets */
      /* value: "3", text: "Viivamaiset tietolajit"} /* linear assets */

      var topElems = ["1", "2", "3"];

      if (_.includes(topElems, a.value) && _.includes(topElems, b.value))
        return a.value > b.value ? 1 : -1;
      else if (_.includes(topElems, a.value))
        return -1;
      else if (_.includes(topElems, b.value))
        return 1;

      return  a.text > b.text ? 1 : -1;
    }

    function multiSelectBoxForMunicipalities() {
      $('#municipalities_search').multiselect({
        search: {
          left:
              '<label class="control-label labelBoxLeft">Kaikki kunnat</label>' +
              '<input type="text" id = "left_municipalities" class="form-control" placeholder="Kuntanimi" />',
          right:
              '<label class="control-label labelBoxRight">Valitut Kunnat</label>' +
              '<input type="text" id = "right_municipalities" class="form-control" placeholder="Kuntanimi" />'
        },
        sort:  {
          left: function (a, b) {
            return municipalitySort(a, b);
          },
          right:   function (a, b) {
            return municipalitySort(a, b);
          }
        },
        fireSearch: function (value) {
          return value.length >= 1;
        }
      });
    }

    function multiSelectBoxForAssets() {
      $('#assets_search').multiselect({
        search: {
          left:
              '<label class="control-label labelBoxLeft">Kaikki Tietolajit</label>' +
              '<input type="text" id = "left_assets" class="form-control" placeholder="Tietolajit Nimi" />',
          right:
              '<label class="control-label labelBoxRight">Valitut Tietolajit</label>' +
              '<input type="text" id = "right_assets" class="form-control" placeholder="Tietolajit Nimi" />'
        },
        sort: {
          left: function (a, b) {
            return assetTypeSort(a, b);
          },
          right:   function (a, b) {
            return assetTypeSort(a, b);
          }
        },
        fireSearch: function (value) {
          return value.length >= 1;
        }
      });
    }

    function saveForm() {
      var formData = {};

      var municipalityValues = $("#municipalities_search_to, select[name*='municipalityNumbers']").find('option');
      formData.municipalityNumbers = _.map(municipalityValues, function (municipality) {
         return municipality.value;
      });

      var assetValues = $("#assets_search_to, select[name*='assetNumbers']").find('option');
      formData.assetNumbers = _.map(assetValues, function (asset) {
         return asset.value;
      });

      me.addSpinner();
      backend.postGenerateCsvReport(formData.municipalityNumbers, formData.assetNumbers,
          function(data) {
            me.removeSpinner();
            addNewRow(data);
          },
          function(xhr) {
            me.removeSpinner();
            if(xhr.status === 403)
              alert("Vain operaattori voi suorittaa Excel-ajon");
            addNewRow(xhr.responseText);
          });
    }

    this.generateWorkList = function (listP) {
      me.addSpinner();
      listP.then(function (result) {

        $('#work-list').html('' +
            '<div style="overflow: auto;">' +
              '<div class="page">' +
                '<div class="content-box">' +
                  '<header id="work-list-header">' + me.title +
                    '<a class="header-link" href="#' + window.applicationModel.getSelectedLayer() + '">Sulje</a>' +
                  '</header>' +
                  '<div class="work-list">' +
                  '</div>' +
              '</div>' +
            '</div>'
        );

        $('#work-list .work-list').html(me.workListItemTable(result));

        populatedMultiSelectBoxes();

        multiSelectBoxForMunicipalities();
        multiSelectBoxForAssets();

        $('#send_btn').click( saveForm );

       $('#municipalities_search_rightSelected, #municipalities_search_leftSelected, #assets_search_rightSelected, #assets_search_leftSelected').on('click', enableSubmitButton);
       $('#municipalities_search, #municipalities_search_to, #assets_search, #assets_search_to').on('dblclick', enableSubmitButton);

        me.getJobs();
        me.removeSpinner();
      });
    };



    function enableSubmitButton() {
      var isMunicipalityEmpty = _.isEmpty($("#municipalities_search_to, select[name*='municipalityNumbers']").find('option'));
      var isAssetEmpty = _.isEmpty($("#assets_search_to, select[name*='assetNumbers']").find('option'));

      var disableStatus = !isAssetEmpty && !isMunicipalityEmpty;
      $('.btn.btn-primary.btn-lg').prop('disabled', !disableStatus);
    }


    this.workListItemTable = function (result) {

      var form =
          '<div class="form form-horizontal" id="csvExport" role="form" >' +
          '<div class="form-group">' +
              '<div class="form-group" disabled="disabled" >' +
                '<div class="row">' +
                  '<div class="col-xs-5">' +
                    '<select id="municipalities_search" class="form-control" multiple="multiple">' +
                    '</select>' +
                  '</div>' +
                  '<div class="col-xs-2">' +
                    '<button type="button" id="municipalities_search_rightSelected" class="action-mode-btn btn btn-block edit-mode-btn btn-primary"> &gt; </button>' +
                    '<button type="button" id="municipalities_search_leftSelected" class="action-mode-btn btn btn-block edit-mode-btn btn-primary"> &lt; </button>' +
                  '</div>' +
                  '<div class="col-xs-5">' +
                    '<select name="municipalityNumbers" id="municipalities_search_to" class="form-control" multiple="multiple">' +
                    '</select>' +
                  '</div>' +
                '</div>' +
              '</div>' +
            '</div>' +
            '<div class="form-group">' +
              '<div class="form-group" disabled="disabled" >' +
                '<div class="row">' +
                  '<div class="col-xs-5">' +
                    '<select id="assets_search" class="form-control" multiple="multiple">' +
                    '</select>' +
                  '</div>' +
                  '<div class="col-xs-2">' +
                    '<button type="button" id="assets_search_rightSelected" class="action-mode-btn btn btn-block edit-mode-btn btn-primary"> &gt; </button>' +
                    '<button type="button" id="assets_search_leftSelected" class="action-mode-btn btn btn-block edit-mode-btn btn-primary"> &lt; </button>' +
                  '</div>' +
                  '<div class="col-xs-5">' +
                    '<select name="assetNumbers" id="assets_search_to" class="form-control" multiple="multiple">' +
                    '</select>' +
                  '</div>' +
                '</div>' +
              '</div>' +
            '</div>' +
            '<div class="form-controls" style="text-align: right;">' +
              '<button id="send_btn" class="btn btn-primary btn-lg" disabled="disabled">Luo raportti</button>' +
            '</div>' +
          '</div>'+
          '<div class="job-status">' +
          '</div>'+
          '<div class="job-content">'+
          '</div>';

      return form;
    };


    var refreshJobs = function() {
      var jobsInProgress = $('.in-progress').map(function(){
        return $(this).attr('id');
      });

      if(!_.isEmpty(jobsInProgress)) {
        backend.getExportsJobsByIds(jobsInProgress.toArray()).then(function(jobs){
          var endedJobs = _.filter(jobs, function(job){return job.status !== 1;});
          _.map(endedJobs, replaceRow);
        });
      } else {
        clearInterval(refresh);
        refresh = null;
      }
    };

    function downloadCsv(event){
      var id = $(event.currentTarget).prop('id');

      backend.getCsvReport(id).then(function(info){
        var csvFile = new Blob(["\ufeff", info.content], {type: "text/csv;charset=ANSI"});

        var auxElem = $('<a />').attr("id","tmp_csv_dwn").attr("href",window.URL.createObjectURL(csvFile)).attr("download", info.fileName);
        auxElem[0].click(); /*trigger href*/
      });
    }

    function replaceRow(job) {
      if (!_.isEmpty(job)) {
        var newRow = jobRow(job);
        $("#"+job.id).replaceWith(newRow);

        $(".job-status-table").find('.job-status-link').on('click', function (event) {
          getJob(event);
        });

        $(".job-status-table").find("#" + job.id + ".btn-download").on('click', function (event) {
          downloadCsv(event);
        });
      }
    }

    var hideImporter = function() {
      $('#csvExport').hide();
      $('.job-content').show();
    };

    var showImporter = function() {
      $('#csvExport').show();
      $('.job-content').empty();
    };

    function getJob(evt){
      var id = $(evt.currentTarget).prop('id');
      backend.getJob(id).then(function(job){
        hideImporter();
        buildJobView(job);
      });
    }

    this.getJobs = function () {
      backend.getExportJobs().then(function(jobs){
        if(!_.isEmpty(jobs))
          $('.job-status').empty().html(buildJobTable(jobs));

        _.forEach(jobs, function(job) {
          if (job.status > 2){
            $(".job-status-table").find('.job-status-link#'+job.id).on('click', function (event) {
              getJob(event);
            });
          }
          else if ( job.status == 2) {
            $(".job-status-table").find("#" + job.id + ".btn-download").on('click', function (event) {
              downloadCsv(event);
            });
          }
        });

        refresh = setInterval(refreshJobs, 3000);
      });
    };

    var buildJobView = function(job) {
      var jobView = $('.job-content');
      jobView.append('' +
          '<div class="job-content-box">' +
          '<header id="error-list-header">' + 'CSV-eräajon virhetilanteet: ' + job.fileName +
          '<a class="header-link" style="cursor: pointer;">Sulje</a>' +
          '</header>' +
          '<div class="error-list">' +
          '</div>'
      );
      jobView.find('.header-link').on('click', function(){
        showImporter();
      });
      $('.error-list').html(job.content);
    };

    function addNewRow(job) {
      if (!_.isEmpty(job)) {
        var newRow = jobRow(job);
        var table = $(".job-status-table tbody tr:first");

        if(_.isEmpty(table))
          $('.job-status').empty().html(buildJobTable([job]));
        else
          table.before(newRow);

        if(!refresh)
          refresh = setInterval(refreshJobs, 3000);

      }
    }

    var buildJobTable = function(jobs) {
      var table = function (jobs) {
        return $('<table>').addClass('job-status-table')
            .append(tableHeaderRow())
            .append(tableBodyRows(jobs));
      };

      var tableHeaderRow = function () {
        return ''+
          '<thead>'+
              '<tr>'+
                '<th id="date" class="csvReport">Päivämäärä</th>'+
                '<th class="csvReport">Tietolajityyppi</th>'+
                '<th id="file" class="csvReport"">Tiedosto</th>'+
                '<th id="exportedAssets" class="csvReport">Tietolajit</th>'+
                '<th id="municipalities" class="csvReport">Kunnat</th>'+
                '<th id="status" class="csvReport">Tila</th>'+
                '<th id="detail" class="csvReport">Raportti</th>'+
            '</tr>'+
          '</thead>';
      };

      var tableBodyRows = function (jobs) {
        return $('<tbody>').attr("id",'tblCsvReport').append(tableContentRows(jobs));
      };

      var tableContentRows = function (jobs) {
        return _.map(jobs, function (job) {
          return jobRow(job).concat('');
        });
      };

      return table(jobs);
    };

    var jobRow = function (job) {
      var btnToDetail = "";

      if (job.status > 2){
        btnToDetail = '<button class="btn btn-block btn-primary job-status-link" id="'+ job.id + '">Avaa</button>';
      }
      else if ( job.status == 2)
        btnToDetail = '<a id="' + job.id + '" class="btn btn-primary btn-download">Lataa CSV<img src="images/icons/export-icon.png"/></a>';


      return '' +
          '<tr class="' + (job.status === 1 ? 'in-progress' : '') + '" id="' + job.id + '">' +
            '<td headers="date" class="csvReport">' + job.createdDate + '</td>' +
            '<td headers="jobName" class="csvReport">Raportti</td>'+
            '<td headers="file" class="csvReport" id="file">' + job.fileName + '</td>' +
            '<td headers="exportedAssets" class="csvReport">'+ job.exportedAssets.replace(/\,/g,"\r\n") + '</td>' +
            '<td headers="municipalities" class="csvReport">'+ job.municipalities.replace(/\,/g,"\r\n") + '</td>' +
            '<td headers="status" class="csvReport">' + getStatusIcon(job.status, job.statusDescription) + '</td>' +
            '<td headers="detail" class="csvReport">' + btnToDetail + '</td>' +
          '</tr>';
    };

    var getStatusIcon = function(status, description) {
      var icon = {
        1: "images/csv-status-icons/clock-outline.png",
        2: "images/csv-status-icons/check-icon.png",
        3: "images/csv-status-icons/not-ok-check-icon.png",
        4: "images/csv-status-icons/error-icon-small.png",
        99: "images/csv-status-icons/unknown-error.png"
      };
      return '<img src="' + icon[status] + '" title="' + description + '"/>';
    };


  };
})(this);