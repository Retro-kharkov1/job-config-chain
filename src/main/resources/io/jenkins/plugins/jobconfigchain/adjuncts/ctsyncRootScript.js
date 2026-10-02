/* @include io.jenkins.plugins.jobconfigchain.adjuncts.ctsyncSharedScript */
/*
 * Global Config Sets list page-level script (on top of ctsyncSharedScript). Loaded via
 * <st:adjunct includes="io.jenkins.plugins.jobconfigchain.adjuncts.ctsyncRootScript"/> at the end of the page,
 * after the Monaco loader. CSP-clean: server-side values arrive as data-* attributes on the
 * page's seed element, and every handler is wired here via addEventListener.
 */

/* ---- This page's own script (ConfigTemplatesRootAction/index.jelly) ---- */

function listStrings() {
  var tpl = document.getElementById('ctsyncListStrings');
  return tpl ? tpl.dataset : {};
}

function announceRowsChanged() {
  if (typeof window !== 'undefined'
      && typeof window.ctsyncRefreshTableFilters === 'function') {
    window.ctsyncRefreshTableFilters();
  }
}

function toggleDeletedRows(show) {
  var table = document.querySelector('table.ctsync-filterable');
  var tbody = table.tBodies[0];
  if (show) {
    var template = document.getElementById('deletedRowsTemplate');
    var rows = template.content.querySelectorAll('tr');
    for (var i = 0; i < rows.length; i++) {
      tbody.appendChild(document.importNode(rows[i], true));
    }
  } else {
    var present = tbody.querySelectorAll('tr.ctsync-row--deleted');
    for (var j = 0; j < present.length; j++) {
      present[j].parentNode.removeChild(present[j]);
    }
  }
  announceRowsChanged();
}

function lifecycleButtons(projectKey, disabled) {
  var buttons = document.querySelectorAll('[data-project-key="' + projectKey + '"]');
  for (var i = 0; i < buttons.length; i++) {
    buttons[i].disabled = disabled;
  }
}

function pageSeed() {
  var el = document.getElementById('ctsyncPageSeed');
  return { rootUrl: el ? el.dataset.rootUrl : '', urlName: el ? el.dataset.urlName : '' };
}

function restoreDeleted(projectKey) {
  // No typed-name confirmation: restoring is fully reversible, and asking for the same
  // ceremony as a purge would train the operator to type through both without reading.
  lifecycleButtons(projectKey, true);
  proxy.restoreConfigSet(JSON.stringify({ projectKey: projectKey }), function (t) {
    var r = t.responseObject();
    if (!r.ok) {
      lifecycleButtons(projectKey, false);
      window.ctsyncNotify(r.error, true);
      return;
    }
    var seed = pageSeed();
    window.location.href = seed.rootUrl + '/manage/' + seed.urlName + '/?restored='
        + encodeURIComponent(projectKey);
  });
}

function purgeJobItems(entries) {
  var items = [];
  for (var i = 0; i < entries.length; i++) {
    items.push({
      text: window.ctsyncFormatMessage(listStrings().jobRow, entries[i].fullName,
          entries[i].versions.join(', ')),
      url: entries[i].url
    });
  }
  return items;
}

function purgeDeleted(projectKey) {
  var d = listStrings();
  lifecycleButtons(projectKey, true);
  window.ctsyncHideBlocked('purgeBlockedBanner');
  proxy.purgePreflight(JSON.stringify({ projectKey: projectKey }), function (t) {
    lifecycleButtons(projectKey, false);
    var r = t.responseObject();
    if (r.blocked) {
      // Purge is refused for ANY reference, including one only an old job version names:
      // there is no restore afterwards to repair a rollback this would have broken.
      window.ctsyncShowBlocked('purgeBlockedBanner',
          window.ctsyncFormatMessage(d.purgeDialogTitle, projectKey),
          [{ intro: d.purgeBlockedIntro,
             items: purgeJobItems(r.activeJobs.concat(r.historicalJobs)) }]);
      return;
    }
    if (!r.ok) { window.ctsyncNotify(r.error, true); return; }
    window.ctsyncConfirmByName({
      expectedName: projectKey,
      title: window.ctsyncFormatMessage(d.purgeDialogTitle, projectKey),
      message: window.ctsyncFormatMessage(d.purgeDialogMessage, projectKey),
      okLabel: d.purgeOkLabel,
      onConfirm: function (typed) {
        proxy.purgeConfigSet(JSON.stringify({ projectKey: projectKey, confirmName: typed }),
            function (t2) {
              var r2 = t2.responseObject();
              if (!r2.ok) { window.ctsyncNotify(r2.error, true); return; }
              var seed = pageSeed();
              window.location.href = seed.rootUrl + '/manage/' + seed.urlName + '/?purged='
                  + encodeURIComponent(projectKey);
            });
      }
    });
  });
}

function reconcilePhantomScroll(wrapEl) {
  if (!wrapEl) { return; }
  var realOverflow = wrapEl.scrollHeight - wrapEl.clientHeight;
  wrapEl.style.overflowY = (realOverflow > 4) ? 'auto' : 'hidden';
}

function reconcilePhantomScrollSoon(wrapEl) {
  reconcilePhantomScroll(wrapEl);
  if (typeof setTimeout === 'function') {
    setTimeout(function () { reconcilePhantomScroll(wrapEl); }, 50);
  }
}

reconcilePhantomScrollSoon(document.getElementById('commonConfigSetsTableWrap'));

// ---- CSP migration: wiring that used to be inline onXxx="..." HTML attributes ----

(function () {
  var showDeletedToggle = document.getElementById('showDeletedToggle');
  if (showDeletedToggle) {
    showDeletedToggle.addEventListener('click', function () {
      toggleDeletedRows(this.checked);
    });
  }

  var createBtn = document.getElementById('newConfigSetCreate');
  if (createBtn) {
    createBtn.addEventListener('click', function () {
      var k = document.getElementById('newProjectKey').value;
      var t = document.querySelector('input[name=newContentTypeRadio]:checked').value;
      window.location.href = createBtn.getAttribute('data-root-url') + '/'
          + createBtn.getAttribute('data-url-name') + '/' + encodeURIComponent(k)
          + '/?contentType=' + encodeURIComponent(t);
    });
  }

  // Restore/purge buttons live inside <template id="deletedRowsTemplate">, cloned into the table
  // body by toggleDeletedRows() above — they do not exist at page-load time, so a direct
  // querySelectorAll+addEventListener here would miss them. Delegation on document covers both
  // the initial (never, in this case) and every future clone.
  document.addEventListener('click', function (e) {
    var restoreBtn = e.target.closest && e.target.closest('.ctsync-restore-btn');
    if (restoreBtn) { restoreDeleted(restoreBtn.getAttribute('data-project-key')); return; }
    var purgeBtn = e.target.closest && e.target.closest('.ctsync-purge-btn');
    if (purgeBtn) { purgeDeleted(purgeBtn.getAttribute('data-project-key')); }
  });
})();
