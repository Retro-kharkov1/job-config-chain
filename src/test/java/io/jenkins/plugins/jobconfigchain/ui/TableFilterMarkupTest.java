package io.jenkins.plugins.jobconfigchain.ui;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the contract between the table-filter block and the tables that opt into it.
 *
 * <p>This area has produced three live defects already, each of them invisible to a build: the
 * filter row was dragged out of {@code thead} into the body by core's sorter; a message row
 * inserted into {@code tbody} made the block's own MutationObserver retrigger itself until the tab
 * froze; and a captioned Actions column received a filter whose every cell held the same button
 * label, so one keystroke hid a table that plainly had rows. None of those were type errors or
 * syntax errors - they were assumptions about markup that nothing checked.
 *
 * <p>So these tests assert on the markup itself rather than on rendered pages: a table that opts
 * into filtering must be shaped the way the block requires, and the block must keep the exclusion
 * rules the failures above taught it. A page-level test would exercise one seeded table; this
 * covers every consumer, including ones added later.
 */
public class TableFilterMarkupTest {

    private static final File UI_RESOURCES =
            new File("src/main/resources/io/jenkins/plugins/jobconfigchain/ui");

    private static final File BLOCK =
            new File(UI_RESOURCES, "SharedBlocks/tableToolsBlock.jelly");

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static List<File> jellyFiles() throws IOException {
        try (var paths = Files.walk(UI_RESOURCES.toPath())) {
            return paths.map(java.nio.file.Path::toFile)
                    .filter(f -> f.getName().endsWith(".jelly"))
                    .sorted()
                    .toList();
        }
    }

    /** Every {@code <table ...>} opening tag in a file, with its attribute text. */
    private static List<String> tableTags(String jelly) {
        List<String> tags = new ArrayList<>();
        Matcher m = Pattern.compile("<table\\b[^>]*>").matcher(jelly);
        while (m.find()) {
            tags.add(m.group());
        }
        return tags;
    }

    @Test
    public void everyFilterableTableAlsoOptsIntoCoreSorting() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (File f : jellyFiles()) {
            for (String tag : tableTags(read(f))) {
                if (tag.contains("ctsync-filterable") && !tag.contains("sortable")) {
                    offenders.add(f.getName() + ": " + tag);
                }
            }
        }
        // The two are designed as one feature: filtering narrows a long table, sorting orders what
        // is left. A table offering only half of that is an oversight, not a decision.
        assertTrue("a ctsync-filterable table must also carry the sortable class: " + offenders,
                offenders.isEmpty());
    }

    @Test
    public void theBaseChainTableIsNeitherSortableNorFilterable() throws Exception {
        String jelly = read(new File(UI_RESOURCES, "SharedBlocks/baseChainBlock.jelly"));
        for (String tag : tableTags(jelly)) {
            // Row order in this table IS the RFC 7396 merge-patch precedence - later rows win on
            // overlapping keys. Reordering it would misstate which value a build actually gets,
            // and hiding a row would make two rows look adjacent in the chain when a hidden row
            // between them is the one that wins.
            assertFalse("the base chain table must never become sortable: " + tag,
                    tag.contains("sortable"));
            assertFalse("the base chain table must never become filterable: " + tag,
                    tag.contains("ctsync-filterable"));
        }
    }

    @Test
    public void filterControlsAreSkippedForColumnsThatDeclareThemselvesUnsortable() throws Exception {
        String block = read(BLOCK);
        // Regression guard for the defect the skeptic review caught on 2026-09-21: the skip rule
        // only tested for an empty caption, so the version-history "Actions" column - captioned,
        // but data-sort-disable - got a text filter. Every one of its cells renders the same
        // button label, so typing any other character hid every row while the table reported "no
        // rows match" for data that was plainly there.
        assertTrue("the filter block must skip columns marked data-sort-disable, not only "
                        + "empty-captioned ones",
                block.contains("th.dataset.sortDisable"));
    }

    @Test
    public void actionColumnsInFilterableTablesDeclareThemselvesUnsortable() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (File f : jellyFiles()) {
            String jelly = read(f);
            if (!jelly.contains("ctsync-filterable")) {
                continue;
            }
            // A header cell whose caption is an actions column must opt out explicitly; otherwise
            // it acquires a filter that can only ever match the button labels inside it.
            Matcher m = Pattern.compile("<th(?![^>]*data-sort-disable)[^>]*>\\$\\{%column\\.actions\\}")
                    .matcher(jelly);
            while (m.find()) {
                offenders.add(f.getName() + ": " + m.group());
            }
        }
        assertEquals("an actions column in a filterable table must carry data-sort-disable: "
                + offenders, List.of(), offenders);
    }

    @Test
    public void theStatusLineIsPlacedOutsideTheScrollingWrapper() throws Exception {
        String block = read(BLOCK);
        // Second defect from the same review: the line was inserted as the table's next sibling,
        // which is inside .ctsync-scroll-table - the element that caps height and scrolls. The
        // count and the "no rows match" message then sat below the fold of the very box they
        // describe, which is exactly what keeping them out of tbody was meant to avoid.
        assertTrue("the status line must anchor to the scroll wrapper, not the table",
                block.contains("closest('.ctsync-scroll-table')"));
        assertFalse("the status line must not be inserted next to the table itself",
                block.contains("table.parentNode.insertBefore(status, table.nextSibling)"));
    }

    @Test
    public void theBlockNeverAddsOrRemovesRowsInTheTableBody() throws Exception {
        String block = read(BLOCK);
        // Third defect: a "no matches" row appended to tbody retriggered the block's own
        // MutationObserver. The callback is delivered as a microtask, so an "applying" flag was
        // already cleared by the time it ran and each pass queued the next - the tab froze. The
        // structural fix is that nothing here touches the row set at all; filtering only toggles
        // display. These calls are what would reintroduce it.
        for (String forbidden : List.of("tbody.appendChild", "tbody.removeChild",
                                        "ctsync-no-match-row")) {
            assertFalse("the filter block must not manipulate tbody rows (found: " + forbidden
                            + ") - core treats every tbody row as sortable data, and mutating the "
                            + "row set retriggers this block's own observer",
                    block.contains(forbidden));
        }
    }

    @Test
    public void everyStringShownToTheUserComesFromTheBundle() throws Exception {
        String block = read(BLOCK);
        // The JS fallbacks exist for a missing template only; the values actually rendered must
        // come from the bundle, so all three locales stay in step.
        for (String key : List.of("filter.placeholder", "filter.count", "filter.noMatches",
                                  "filter.any")) {
            assertTrue("the block must read " + key + " from its bundle",
                    block.contains("${%" + key + "}"));
        }
    }
}
