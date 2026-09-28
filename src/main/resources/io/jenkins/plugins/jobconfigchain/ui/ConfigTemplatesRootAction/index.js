/*
 * ConfigTemplatesRootAction/index.jelly's page-level script.
 *
 * CSP migration (Jenkins hosting requirement, see
 * https://www.jenkins.io/doc/developer/security/csp/): moved out of this page's inline <script>
 * body into this external file, loaded via <script src="${h.getViewResource(it, 'index.js')}"/>.
 * Server-side values that used to be interpolated via Jelly ${...} directly into the script text
 * now arrive as data-* attributes on stable elements (a static .js file is never Jelly-evaluated)
 * — see the "data-*" reads below and their matching attributes in index.jelly. Every former inline
 * onclick="..."/onchange="..." attribute in the markup is now wired here via addEventListener
 * (event delegation for rows built/cloned at runtime, direct binding for static elements),
 * preserving the exact same trigger conditions and function calls as before.
 */

/* ---- SharedBlocks/tableToolsBlock.jelly (moved out of its own inline <script>) ----
   Duplicated identically in ConfigTemplatesJobAction/index.js and CommonConfigSetPage/index.js —
   see tableToolsBlock.jelly's own comment for why this fragment's CSS/JS cannot be factored into
   one shared file under this migration. Keep all three copies byte-identical. */
(function () {
  function strings() {
    var tpl = document.getElementById('ctsyncTableToolsStrings');
    return {
      placeholder: (tpl && tpl.dataset.filterPlaceholder) || 'Filter',
      count: (tpl && tpl.dataset.countLabel) || '{0} of {1}',
      noMatches: (tpl && tpl.dataset.noMatches) || 'No rows match the filter',
      any: (tpl && tpl.dataset.anyLabel) || 'Any'
    };
  }

  function format(template, a, b) {
    return template.replace('{0}', String(a)).replace('{1}', String(b));
  }

  function dataRows(tbody) {
    return Array.prototype.filter.call(tbody.rows, function (tr) {
      return !tr.classList.contains('ctsync-empty-state-row');
    });
  }

  function whenSortingWired(table, callback) {
    if (!table.classList.contains('sortable') || table.sortable) { callback(); return; }
    var attempts = 0;
    (function poll() {
      if (table.sortable || attempts++ > 50) { callback(); return; }
      setTimeout(poll, 20);
    })();
  }

  function buildControl(th, s) {
    var enumerable = th.dataset.ctsyncFilter === 'enum';
    var control;
    if (enumerable) {
      control = document.createElement('select');
      control.className = 'jenkins-select__input ctsync-filter-input';
    } else {
      control = document.createElement('input');
      control.type = 'search';
      control.className = 'jenkins-input ctsync-filter-input';
      control.placeholder = s.placeholder;
    }
    return { control: control, enumerable: enumerable };
  }

  function wire(table) {
    var thead = table.tHead;
    var tbody = table.tBodies[0];
    if (!thead || !tbody || table.dataset.ctsyncFilterWired === 'true') { return; }
    table.dataset.ctsyncFilterWired = 'true';

    var s = strings();
    var inputs = [];

    Array.prototype.forEach.call(thead.rows[0].cells, function (th, index) {
      var caption = th.textContent.trim();
      if (caption === '' || th.dataset.sortDisable) { return; }

      var built = buildControl(th, s);
      var control = built.control;
      control.setAttribute('aria-label', s.placeholder + ': ' + caption);
      control.addEventListener('click', function (e) { e.stopPropagation(); });
      control.addEventListener('mousedown', function (e) { e.stopPropagation(); });
      control.addEventListener(built.enumerable ? 'change' : 'input', function () {
        apply(table);
      });
      th.appendChild(control);
      inputs.push({ index: index, input: control, enumerable: built.enumerable });
    });

    table.ctsyncFilterInputs = inputs;
    syncEnumOptions(table);

    var status = document.createElement('div');
    status.className = 'ctsync-filter-status';
    status.hidden = true;
    var scrollWrap = table.closest ? table.closest('.ctsync-scroll-table') : null;
    var anchor = scrollWrap || table;
    anchor.parentNode.insertBefore(status, anchor.nextSibling);
    table.ctsyncFilterStatus = status;

    if (typeof window !== 'undefined' && window.MutationObserver) {
      new MutationObserver(function () {
        syncEnumOptions(table);
        apply(table);
      }).observe(tbody, { childList: true });
    }
  }

  function syncEnumOptions(table) {
    var s = strings();
    (table.ctsyncFilterInputs || []).forEach(function (f) {
      if (!f.enumerable) { return; }
      var previous = f.input.value;
      var seen = [];
      dataRows(table.tBodies[0]).forEach(function (tr) {
        var cell = tr.cells[f.index];
        var text = cell ? cell.textContent.trim() : '';
        if (text !== '' && seen.indexOf(text) === -1) { seen.push(text); }
      });
      seen.sort();
      f.input.innerHTML = '';
      var any = document.createElement('option');
      any.value = '';
      any.text = s.any;
      f.input.appendChild(any);
      seen.forEach(function (value) {
        var option = document.createElement('option');
        option.value = value;
        option.text = value;
        f.input.appendChild(option);
      });
      f.input.value = seen.indexOf(previous) !== -1 ? previous : '';
    });
  }

  function apply(table) {
    var s = strings();
    var terms = (table.ctsyncFilterInputs || [])
        .map(function (f) {
          return { index: f.index, enumerable: f.enumerable,
                   value: f.input.value.trim().toLowerCase() };
        })
        .filter(function (f) { return f.value !== ''; });

    var rows = dataRows(table.tBodies[0]);
    var visible = 0;
    rows.forEach(function (tr) {
      var match = terms.every(function (t) {
        var cell = tr.cells[t.index];
        if (!cell) { return false; }
        var text = cell.textContent.trim().toLowerCase();
        return t.enumerable ? text === t.value : text.indexOf(t.value) !== -1;
      });
      tr.style.display = match ? '' : 'none';
      if (match) { visible++; }
    });

    var status = table.ctsyncFilterStatus;
    if (status) {
      status.hidden = terms.length === 0;
      status.textContent = (terms.length > 0 && visible === 0 && rows.length > 0)
          ? s.noMatches
          : format(s.count, visible, rows.length);
    }
  }

  function init() {
    Array.prototype.forEach.call(document.querySelectorAll('table.ctsync-filterable'),
      function (table) {
        whenSortingWired(table, function () { wire(table); });
      });
  }

  var refreshAll = function () {
    Array.prototype.forEach.call(document.querySelectorAll('table.ctsync-filterable'),
      function (table) {
        if (table.ctsyncFilterInputs) {
          syncEnumOptions(table);
          apply(table);
        }
      });
  };
  if (typeof window !== 'undefined') {
    window.ctsyncRefreshTableFilters = refreshAll;
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

/* ---- SharedBlocks/confirmByNameBlock.jelly (moved out of its own inline <script>) ----
   Duplicated identically in ConfigTemplatesJobAction/index.js and CommonConfigSetPage/index.js. */
(function () {
  function strings() {
    var tpl = document.getElementById('ctsyncConfirmStrings');
    return {
      cancel: (tpl && tpl.dataset.cancelLabel) || 'Cancel',
      mismatch: (tpl && tpl.dataset.nameMismatch) || 'The typed name does not match.'
    };
  }

  function hasDialog() {
    return typeof window !== 'undefined' && window.dialog
        && typeof window.dialog.prompt === 'function';
  }

  function format(template) {
    var args = arguments;
    return String(template).replace(/\{(\d+)\}/g, function (placeholder, index) {
      var value = args[Number(index) + 1];
      return value === undefined ? placeholder : String(value);
    });
  }

  function confirmByName(opts) {
    var s = strings();
    function proceed(typed) {
      if (typed === null || typed === undefined) { return; }
      if (typed.replace(/^\s+|\s+$/g, '') !== opts.expectedName) {
        notify(s.mismatch, true);
        return;
      }
      opts.onConfirm(typed);
    }
    if (!hasDialog()) {
      proceed(window.prompt(opts.message));
      return;
    }
    var promptResult = window.dialog.prompt(opts.title, {
      message: opts.message,
      allowEmpty: false,
      type: 'destructive',
      okText: opts.okLabel,
      cancelText: s.cancel
    });
    // Bug fix (2026-09-28): core's own dialog only gates its OK button on "is the field
    // non-empty" (allowEmpty:false) — it has no notion of the exact name this dialog requires, so
    // the button used to become clickable on ANY typed text, not just a match. proceed() above
    // already refused a mismatch (it never called opts.onConfirm), so this was never a
    // data-safety hole, but it broke the disabled-until-correct promise the button visually
    // makes: a mismatched Enter/click closed the dialog and only then reported the mismatch,
    // instead of never being clickable in the first place. dialog.prompt() renders (and shows)
    // its dialog synchronously before returning the pending promise above — the same reason the
    // dialog is already visible to the operator the instant this call returns — so the freshly
    // opened <dialog> is reachable immediately afterwards, with no need to wait on anything async.
    enforceNameMatchOnOpenDialog(opts.expectedName, s.cancel);
    promptResult.then(proceed).catch(function () {
      // Cancelling rejects with no argument — swallow to avoid an unhandled-rejection console error.
    });
  }

  // Bug fix (2026-09-28), see confirmByName's own comment above for the "why". This codebase never
  // has more than one of these modal prompts open at a time, so the freshly opened dialog is
  // always the LAST <dialog> element in the document at this point.
  function enforceNameMatchOnOpenDialog(expectedName, cancelLabel) {
    if (typeof document === 'undefined' || !document.querySelectorAll) { return; }
    var dialogs = document.querySelectorAll('dialog');
    var dlg = dialogs[dialogs.length - 1];
    if (!dlg) { return; }
    wireDialogNameInput(dlg, expectedName, cancelLabel);
  }

  function findDialogOkButton(dlg, cancelLabel) {
    // Verified against a live Jenkins 2.568.3 controller (2026-09-28): core's dialog.prompt()
    // never renders a "...--destructive" class - the OK button's actual class string is
    // 'jenkins-button jenkins-button--primary jenkins-!-destructive-color' even for
    // type:'destructive', and that combination is theming, not addressing (it can change with
    // dialog type or a future skin). What core DOES render consistently is a data-id attribute
    // pairing each control with its role - data-id="ok", data-id="cancel", data-id="input" - so
    // that is the hook used here.
    var byDataId = dlg.querySelector('button[data-id="ok"]');
    if (byDataId) { return byDataId; }
    // Defensive fallback only: not known to be exercised against any real Jenkins core dialog
    // markup (data-id="ok" has matched on every version checked so far). Kept in case a future
    // core version drops data-id entirely - picks whichever dialog button is not labelled exactly
    // like the Cancel button we asked for (matched by its own rendered text, so this stays correct
    // under localization instead of guessing an English word).
    var buttons = Array.prototype.slice.call(dlg.querySelectorAll('button'));
    for (var i = 0; i < buttons.length; i++) {
      if (buttons[i].textContent.replace(/^\s+|\s+$/g, '') !== cancelLabel) { return buttons[i]; }
    }
    return null;
  }

  function wireDialogNameInput(dlg, expectedName, cancelLabel) {
    var input = dlg.querySelector('input[type="text"], input:not([type])');
    if (!input) { return; }
    function sync() {
      var btn = findDialogOkButton(dlg, cancelLabel);
      if (!btn) { return; }
      var typed = input.value.replace(/^\s+|\s+$/g, '');
      // Only ever forces the button back to disabled on a mismatch — never re-enables it here, so
      // core's own allowEmpty:false gate (which DOES re-enable it once the field stops being
      // empty, on every keystroke) always still runs first, exactly as before this fix.
      if (typed !== expectedName) { btn.disabled = true; }
    }
    input.addEventListener('input', sync);
    sync();
  }

  function showBlocked(bannerId, title, sections) {
    var banner = document.getElementById(bannerId);
    var plain = [];
    if (banner) {
      banner.innerHTML = '';
      var heading = document.createElement('strong');
      heading.textContent = title;
      banner.appendChild(heading);
    }
    for (var i = 0; i < sections.length; i++) {
      var section = sections[i];
      if (!section.items || section.items.length === 0) { continue; }
      plain.push(section.intro);
      if (banner) {
        var intro = document.createElement('div');
        intro.className = 'ctsync-blocked-note';
        intro.textContent = section.intro;
        banner.appendChild(intro);
        var list = document.createElement('ul');
        banner.appendChild(list);
      }
      for (var j = 0; j < section.items.length; j++) {
        plain.push('  ' + section.items[j].text);
        if (banner) {
          var item = document.createElement('li');
          if (section.items[j].url) {
            var link = document.createElement('a');
            link.href = section.items[j].url;
            link.textContent = section.items[j].text;
            item.appendChild(link);
          } else {
            item.textContent = section.items[j].text;
          }
          list.appendChild(item);
        }
      }
    }
    if (banner) { banner.style.display = ''; }
    if (typeof window !== 'undefined' && window.dialog
        && typeof window.dialog.alert === 'function') {
      window.dialog.alert(title, { message: plain.join('\n'), type: 'destructive' });
    } else {
      notify(title, true);
    }
  }

  function hideBlocked(bannerId) {
    var banner = document.getElementById(bannerId);
    if (banner) {
      banner.innerHTML = '';
      banner.style.display = 'none';
    }
  }

  function notify(message, isError) {
    if (typeof window === 'undefined' || !window.notificationBar) { return; }
    window.notificationBar.show(message,
        isError ? window.notificationBar.ERROR : window.notificationBar.SUCCESS);
  }

  function formatTimestamp(epochMillis) {
    var value = Number(epochMillis);
    if (!value) { return ''; }
    return new Date(value).toLocaleString();
  }

  if (typeof window !== 'undefined') {
    window.ctsyncConfirmByName = confirmByName;
    window.ctsyncShowBlocked = showBlocked;
    window.ctsyncHideBlocked = hideBlocked;
    window.ctsyncNotify = notify;
    window.ctsyncFormatTimestamp = formatTimestamp;
    window.ctsyncFormatMessage = format;
  }
})();

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
