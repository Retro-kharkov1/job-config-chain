#!/usr/bin/env bash
# Runs inside the maven container. Usage: in-container.sh baseline|after
# Copies the source into the container's own filesystem (fast), optionally reverses the UI-38/UI-39
# changes to reproduce the unchanged tree (baseline), then runs the full suite.
set -uo pipefail
MODE="${1:?mode baseline|after}"
rm -rf /work && mkdir -p /work
tar -C /src --exclude=./target --exclude=./.git -cf - . | tar -C /work -xf -
cd /work
sed -i 's/\r$//' mvnw 2>/dev/null || true

if [ "$MODE" = "baseline" ]; then
  echo "==> reversing UI-38/UI-39 changes to reproduce the unchanged tree"
  PH=src/main/resources/io/jenkins/plugins/jobconfigchain/ui/PageHelp
  mkdir -p "$PH"
  mv src/main/webapp/help/* "$PH"/
  rmdir src/main/webapp/help
  cp /orig/PageHelp.java src/main/java/io/jenkins/plugins/jobconfigchain/ui/PageHelp.java
  for f in $(grep -rl "/plugin/job-config-chain/help/help-" src/main --include=*.jelly); do
    sed -i -E 's#"/plugin/job-config-chain/help/help-([A-Za-z]+)\.html"#"/job-config-chain-help/\1"#' "$f"
  done
  T=src/test/java/io/jenkins/plugins/jobconfigchain/ui
  sed -i 's/\r$//' $T/CspCleanPagesTest.java src/test/java/io/jenkins/plugins/jobconfigchain/merge/tree/XmlTreeAdapterHardeningTest.java
  sed -i 's#new File("src/main/webapp/help")#new File("src/main/resources/io/jenkins/plugins/jobconfigchain/ui/PageHelp")#' $T/BundleParityTest.java
  sed -i 's#HELP_PATH + "help-" + name + ".html", null#PageHelp.URL_NAME + "/" + name, null#' $T/CspCleanPagesTest.java
  perl -0pi -e '
    my $from = <<'"'"'X'"'"';
" + HELP_PATH + "help-" + name + "\\.html\"")
X
    my $to = <<'"'"'X'"'"';
" + PageHelp.URL_NAME + "/" + name + "\"")
X
    $from =~ s/\n$//; $to =~ s/\n$//; s/\Q$from\E/$to/;' $T/CspCleanPagesTest.java
  perl -0pi -e 'BEGIN{local $/; open F,"/orig/csp-block.txt"; $o=<F>; close F} s/    \@Test\n    public void theHelpFilesAreServed.*?(?=    \@Test\n    public void theArraysAreReplaced)/$o/s' $T/CspCleanPagesTest.java
  perl -0pi -e 's/    \@Test\n    public void parser_rejectsAnExternalEntity.*?(?=    \@Test\n    public void harden_doesNotThrow)//s' \
    src/test/java/io/jenkins/plugins/jobconfigchain/merge/tree/XmlTreeAdapterHardeningTest.java
  grep -rn "HELP_PATH" $T/CspCleanPagesTest.java && echo "WARN: baseline reversal incomplete" || true
fi

echo "==> mvn -B clean verify ($MODE) started $(date -u +%FT%TZ)"
mvn -B -Dmaven.clean.failOnError=false clean verify
RC=$?
echo "==> mvn exit code: $RC ($MODE) finished $(date -u +%FT%TZ)"
echo "==> surefire totals ($MODE)"
grep -h "Tests run:.*Fail" target/surefire-reports/*.txt 2>/dev/null | awk -F'[:,]' '{r+=$2;f+=$4;e+=$6;s+=$8} END{print "tests="r" failures="f" errors="e" skipped="s}'
grep -E "Tests run:.*Fail.*Skipped: [0-9]+$" -m1 /dev/null || true
ls target/*.hpi 2>/dev/null
exit $RC
