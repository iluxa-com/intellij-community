/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.diff.tools.simple;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.codeInsight.hint.HintUtil;
import com.intellij.diff.DiffContext;
import com.intellij.diff.comparison.ByLine;
import com.intellij.diff.comparison.ComparisonPolicy;
import com.intellij.diff.comparison.DiffTooBigException;
import com.intellij.diff.comparison.MergeUtil;
import com.intellij.diff.comparison.iterables.FairDiffIterable;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.fragments.MergeLineFragment;
import com.intellij.diff.requests.ContentDiffRequest;
import com.intellij.diff.requests.DiffRequest;
import com.intellij.diff.tools.util.*;
import com.intellij.diff.tools.util.FoldingModelSupport.SimpleThreesideFoldingModel;
import com.intellij.diff.tools.util.base.IgnorePolicy;
import com.intellij.diff.tools.util.threeside.ThreesideTextDiffViewer;
import com.intellij.diff.util.DiffDividerDrawUtil;
import com.intellij.diff.util.DiffUserDataKeysEx.ScrollToPolicy;
import com.intellij.diff.util.DiffUtil;
import com.intellij.diff.util.Side;
import com.intellij.diff.util.ThreeSide;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.LightweightHint;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.ButtonlessScrollBarUI;
import org.jetbrains.annotations.CalledInAwt;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

// TODO: extract common methods with Twoside
public class SimpleThreesideDiffViewer extends ThreesideTextDiffViewer {
  public static final Logger LOG = Logger.getInstance(SimpleThreesideDiffViewer.class);

  @NotNull private final SyncScrollSupport.SyncScrollable mySyncScrollable1;
  @NotNull private final SyncScrollSupport.SyncScrollable mySyncScrollable2;

  @NotNull private final PrevNextDifferenceIterable myPrevNextDifferenceIterable;
  @NotNull private final MyStatusPanel myStatusPanel;

  @NotNull private final List<SimpleThreesideDiffChange> myDiffChanges = new ArrayList<SimpleThreesideDiffChange>();
  @NotNull private final List<SimpleThreesideDiffChange> myInvalidDiffChanges = new ArrayList<SimpleThreesideDiffChange>();

  @NotNull private final SimpleThreesideFoldingModel myFoldingModel;

  public SimpleThreesideDiffViewer(@NotNull DiffContext context, @NotNull DiffRequest request) {
    super(context, (ContentDiffRequest)request);

    mySyncScrollable1 = new MySyncScrollable(Side.LEFT);
    mySyncScrollable2 = new MySyncScrollable(Side.RIGHT);
    myPrevNextDifferenceIterable = new MyPrevNextDifferenceIterable();
    myStatusPanel = new MyStatusPanel();
    myFoldingModel = new SimpleThreesideFoldingModel(myEditors.toArray(new EditorEx[3]), this);
  }

  @Override
  protected void onInit() {
    super.onInit();
    myContentPanel.setPainter(new MyDividerPainter(Side.LEFT), Side.LEFT);
    myContentPanel.setPainter(new MyDividerPainter(Side.RIGHT), Side.RIGHT);
    myContentPanel.setScrollbarPainter(new MyScrollbarPainter());
  }

  @Override
  protected void onDisposeAwt() {
    destroyChangedBlocks();
    super.onDisposeAwt();
  }

  @NotNull
  @Override
  protected List<AnAction> createToolbarActions() {
    List<AnAction> group = new ArrayList<AnAction>();

    group.add(new MyIgnorePolicySettingAction());
    //group.add(new MyHighlightPolicySettingAction()); // TODO
    group.add(new MyToggleExpandByDefaultAction());
    group.add(new ToggleAutoScrollAction());
    group.add(myEditorSettingsAction);

    group.add(Separator.getInstance());
    group.add(new ShowLeftBasePartialDiffAction());
    group.add(new ShowBaseRightPartialDiffAction());
    group.add(new ShowLeftRightPartialDiffAction());

    return group;
  }

  @Nullable
  @Override
  protected List<AnAction> createPopupActions() {
    List<AnAction> group = new ArrayList<AnAction>();

    group.add(Separator.getInstance());
    group.add(new MyIgnorePolicySettingAction().getPopupGroup());
    //group.add(Separator.getInstance());
    //group.add(new MyHighlightPolicySettingAction().getPopupGroup());
    group.add(Separator.getInstance());
    group.add(new ToggleAutoScrollAction());
    group.add(new MyToggleExpandByDefaultAction());

    return group;
  }

  @Override
  protected void updateContextHints() {
    super.updateContextHints();
    myFoldingModel.updateContext(myRequest, getTextSettings().isExpandByDefault());
  }

  //
  // Diff
  //

  @Override
  protected void onSlowRediff() {
    super.onSlowRediff();
    myStatusPanel.setBusy(true);
  }

  @Override
  @NotNull
  protected Runnable performRediff(@NotNull final ProgressIndicator indicator) {
    try {
      indicator.checkCanceled();

      DiffContent[] rawContents = myRequest.getContents();
      final DocumentContent[] contents = new DocumentContent[3];
      final Document[] documents = new Document[3];
      contents[0] = (DocumentContent)rawContents[0];
      contents[1] = (DocumentContent)rawContents[1];
      contents[2] = (DocumentContent)rawContents[2];
      documents[0] = contents[0].getDocument();
      documents[1] = contents[1].getDocument();
      documents[2] = contents[2].getDocument();

      DocumentData data = ApplicationManager.getApplication().runReadAction(new Computable<DocumentData>() {
        @Override
        public DocumentData compute() {
          CharSequence[] sequences = new CharSequence[3];
          sequences[0] = documents[0].getImmutableCharSequence();
          sequences[1] = documents[1].getImmutableCharSequence();
          sequences[2] = documents[2].getImmutableCharSequence();

          long[] stamps = new long[3];
          stamps[0] = documents[0].getModificationStamp();
          stamps[1] = documents[1].getModificationStamp();
          stamps[2] = documents[2].getModificationStamp();

          return new DocumentData(stamps, sequences);
        }
      });

      // TODO: cache results
      CharSequence[] sequences = data.getSequences();
      ComparisonPolicy comparisonPolicy = getIgnorePolicy().getComparisonPolicy();
      FairDiffIterable fragments1 = ByLine.compareTwoStepFair(sequences[1], sequences[0], comparisonPolicy, indicator);
      FairDiffIterable fragments2 = ByLine.compareTwoStepFair(sequences[1], sequences[2], comparisonPolicy, indicator);
      List<MergeLineFragment> mergeFragments = MergeUtil.buildFair(fragments1, fragments2, indicator);

      return apply(mergeFragments, data.getStamps(), comparisonPolicy);
    }
    catch (DiffTooBigException ignore) {
      return new Runnable() {
        @Override
        public void run() {
          clearDiffPresentation();
          myPanel.addTooBigContentNotification();
        }
      };
    }
    catch (ProcessCanceledException ignore) {
      return new Runnable() {
        @Override
        public void run() {
          clearDiffPresentation();
          myPanel.addOperationCanceledNotification();
        }
      };
    }
    catch (Exception e) {
      LOG.error(e);
      return new Runnable() {
        @Override
        public void run() {
          clearDiffPresentation();
          myPanel.addDiffErrorNotification();
        }
      };
    }
    catch (final Error e) {
      return new Runnable() {
        @Override
        public void run() {
          clearDiffPresentation();
          myPanel.addDiffErrorNotification();
          throw e;
        }
      };
    }
  }

  @NotNull
  private Runnable apply(@NotNull final List<MergeLineFragment> fragments,
                         @NotNull final long[] stamps,
                         @NotNull final ComparisonPolicy comparisonPolicy) {
    return new Runnable() {
      @Override
      public void run() {
        if (myEditors.get(0).getDocument().getModificationStamp() != stamps[0]) return;
        if (myEditors.get(1).getDocument().getModificationStamp() != stamps[1]) return;
        if (myEditors.get(2).getDocument().getModificationStamp() != stamps[2]) return;

        myFoldingModel.updateContext(myRequest, getTextSettings().isExpandByDefault());
        clearDiffPresentation();

        for (MergeLineFragment fragment : fragments) {
          myDiffChanges.add(new SimpleThreesideDiffChange(fragment, myEditors, comparisonPolicy));
        }

        myFoldingModel.install(fragments, myRequest, getTextSettings().isExpandByDefault(), getTextSettings().getContextRange());

        scrollOnRediff();

        myContentPanel.repaintDividers();
        myStatusPanel.update();
      }
    };
  }

  private void clearDiffPresentation() {
    myStatusPanel.setBusy(false);
    myPanel.resetNotifications();
    destroyChangedBlocks();
  }

  private void destroyChangedBlocks() {
    for (SimpleThreesideDiffChange change : myDiffChanges) {
      change.destroyHighlighter();
    }
    myDiffChanges.clear();

    for (SimpleThreesideDiffChange change : myInvalidDiffChanges) {
      change.destroyHighlighter();
    }
    myInvalidDiffChanges.clear();

    myContentPanel.repaintDividers();
    myStatusPanel.update();
  }

  //
  // Impl
  //

  @Override
  @CalledInAwt
  protected void onBeforeDocumentChange(@NotNull DocumentEvent e) {
    super.onBeforeDocumentChange(e);
    if (myDiffChanges.isEmpty()) return;

    ThreeSide side;
    if (e.getDocument() == myEditors.get(0).getDocument()) {
      side = ThreeSide.LEFT;
    }
    else if (e.getDocument() == myEditors.get(1).getDocument()) {
      side = ThreeSide.BASE;
    }
    else if (e.getDocument() == myEditors.get(2).getDocument()) {
      side = ThreeSide.RIGHT;
    }
    else {
      LOG.warn("Unknown document changed");
      return;
    }

    int offset1 = e.getOffset();
    int offset2 = e.getOffset() + e.getOldLength();

    if (StringUtil.endsWithChar(e.getOldFragment(), '\n') &&
        StringUtil.endsWithChar(e.getNewFragment(), '\n')) {
      offset2--;
    }

    int line1 = e.getDocument().getLineNumber(offset1);
    int line2 = e.getDocument().getLineNumber(offset2) + 1;
    int shift = StringUtil.countNewLines(e.getNewFragment()) - StringUtil.countNewLines(e.getOldFragment());

    List<SimpleThreesideDiffChange> invalid = new ArrayList<SimpleThreesideDiffChange>();
    for (SimpleThreesideDiffChange change : myDiffChanges) {
      if (change.processChange(line1, line2, shift, side)) {
        invalid.add(change);
      }
    }

    if (!invalid.isEmpty()) {
      myDiffChanges.removeAll(invalid);
      myInvalidDiffChanges.addAll(invalid);
    }
  }

  @Override
  protected void onDocumentChange(@NotNull DocumentEvent e) {
    super.onDocumentChange(e);
    myFoldingModel.onDocumentChanged(e);
  }

  @CalledInAwt
  @Override
  protected boolean doScrollToChange(@NotNull ScrollToPolicy scrollToPolicy) {
    if (myDiffChanges.isEmpty()) return false;

    SimpleThreesideDiffChange targetChange;
    switch (scrollToPolicy) {
      case FIRST_CHANGE:
        targetChange = myDiffChanges.get(0);
        break;
      case LAST_CHANGE:
        targetChange = myDiffChanges.get(myDiffChanges.size() - 1);
        break;
      default:
        throw new IllegalArgumentException(scrollToPolicy.name());
    }

    EditorEx editor = getCurrentEditor();
    int line = targetChange.getStartLine(getCurrentSide());
    DiffUtil.scrollEditor(editor, line);

    return true;
  }

  @NotNull
  private IgnorePolicy getIgnorePolicy() {
    IgnorePolicy policy = getTextSettings().getIgnorePolicy();
    if (policy == IgnorePolicy.IGNORE_WHITESPACES_CHUNKS) return IgnorePolicy.IGNORE_WHITESPACES;
    return policy;
  }

  //
  // Getters
  //

  int getCurrentStartLine(@NotNull SimpleThreesideDiffChange change) {
    return change.getStartLine(getCurrentSide());
  }

  int getCurrentEndLine(@NotNull SimpleThreesideDiffChange change) {
    return change.getEndLine(getCurrentSide());
  }

  @NotNull
  List<SimpleThreesideDiffChange> getDiffChanges() {
    return myDiffChanges;
  }

  @NotNull
  @Override
  protected SyncScrollSupport.SyncScrollable getSyncScrollable(@NotNull Side side) {
    return side.selectN(mySyncScrollable1, mySyncScrollable2);
  }

  @NotNull
  @Override
  protected JComponent getStatusPanel() {
    return myStatusPanel;
  }

  //
  // Misc
  //

  @SuppressWarnings("MethodOverridesStaticMethodOfSuperclass")
  public static boolean canShowRequest(@NotNull DiffContext context, @NotNull DiffRequest request) {
    return ThreesideTextDiffViewer.canShowRequest(context, request);
  }

  //
  // Actions
  //

  private class MyPrevNextDifferenceIterable implements PrevNextDifferenceIterable {
    @Override
    public void notify(@NotNull String message) {
      final LightweightHint hint = new LightweightHint(HintUtil.createInformationLabel(message));
      HintManagerImpl.getInstanceImpl().showEditorHint(hint, getCurrentEditor(), HintManager.UNDER,
                                                       HintManager.HIDE_BY_ANY_KEY |
                                                       HintManager.HIDE_BY_TEXT_CHANGE |
                                                       HintManager.HIDE_BY_SCROLLING,
                                                       0, false);
    }

    @Override
    public boolean canGoNext() {
      if (myDiffChanges.isEmpty()) return false;

      EditorEx editor = getCurrentEditor();
      int line = editor.getCaretModel().getLogicalPosition().line;
      if (line == editor.getDocument().getLineCount() - 1) return false;

      SimpleThreesideDiffChange lastChange = myDiffChanges.get(myDiffChanges.size() - 1);
      if (getCurrentStartLine(lastChange) <= line) return false;

      return true;
    }

    @Override
    public void goNext() {
      EditorEx editor = getCurrentEditor();

      int line = editor.getCaretModel().getLogicalPosition().line;

      SimpleThreesideDiffChange next = null;
      for (int i = 0; i < myDiffChanges.size(); i++) {
        SimpleThreesideDiffChange change = myDiffChanges.get(i);
        if (getCurrentStartLine(change) <= line) continue;

        next = change;
        break;
      }

      assert next != null;

      DiffUtil.scrollToLineAnimated(editor, getCurrentStartLine(next));
    }

    @Override
    public boolean canGoPrev() {
      if (myDiffChanges.isEmpty()) return false;

      EditorEx editor = getCurrentEditor();
      int line = editor.getCaretModel().getLogicalPosition().line;
      if (line == 0) return false;

      SimpleThreesideDiffChange firstChange = myDiffChanges.get(0);
      if (getCurrentEndLine(firstChange) > line) return false;
      if (getCurrentStartLine(firstChange) >= line) return false;

      return true;
    }

    @Override
    public void goPrev() {
      EditorEx editor = getCurrentEditor();

      int line = editor.getCaretModel().getLogicalPosition().line;

      SimpleThreesideDiffChange prev = null;
      for (int i = 0; i < myDiffChanges.size(); i++) {
        SimpleThreesideDiffChange change = myDiffChanges.get(i);

        SimpleThreesideDiffChange next = i < myDiffChanges.size() - 1 ? myDiffChanges.get(i + 1) : null;
        if (next == null || getCurrentEndLine(next) > line || getCurrentStartLine(next) >= line) {
          prev = change;
          break;
        }
      }

      assert prev != null;

      DiffUtil.scrollToLineAnimated(editor, getCurrentStartLine(prev));
    }
  }

  private class MyToggleExpandByDefaultAction extends ToggleExpandByDefaultAction {
    @Override
    protected void expandAll(boolean expand) {
      myFoldingModel.expandAll(expand);
    }
  }

  private class MyIgnorePolicySettingAction extends IgnorePolicySettingAction {
    @NotNull
    @Override
    protected IgnorePolicy getCurrentSetting() {
      return getIgnorePolicy();
    }

    @NotNull
    @Override
    protected List<IgnorePolicy> getAvailableSettings() {
      ArrayList<IgnorePolicy> settings = ContainerUtil.newArrayList(IgnorePolicy.values());
      settings.remove(IgnorePolicy.IGNORE_WHITESPACES_CHUNKS);
      return settings;
    }
  }

  //
  // Helpers
  //

  @Nullable
  @Override
  public Object getData(@NonNls String dataId) {
    if (DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE.is(dataId)) {
      return myPrevNextDifferenceIterable;
    }
    else {
      return super.getData(dataId);
    }
  }

  private class MySyncScrollable extends BaseSyncScrollable {
    @NotNull private final Side mySide;

    public MySyncScrollable(@NotNull Side side) {
      mySide = side;
    }

    @Override
    public boolean isSyncScrollEnabled() {
      return getTextSettings().isEnableSyncScroll();
    }

    @Override
    protected void processHelper(@NotNull ScrollHelper helper) {
      ThreeSide left = mySide.selectN(ThreeSide.LEFT, ThreeSide.BASE);
      ThreeSide right = mySide.selectN(ThreeSide.BASE, ThreeSide.RIGHT);

      if (!helper.process(0, 0)) return;
      for (SimpleThreesideDiffChange diffChange : myDiffChanges) {
        if (!helper.process(diffChange.getStartLine(left), diffChange.getStartLine(right))) return;
        if (!helper.process(diffChange.getEndLine(left), diffChange.getEndLine(right))) return;
      }
      helper.process(left.selectN(myEditors).getDocument().getLineCount(), right.selectN(myEditors).getDocument().getLineCount());
    }
  }

  private class MyDividerPaintable implements DiffDividerDrawUtil.DividerPaintable {
    @NotNull private final Side mySide;

    public MyDividerPaintable(@NotNull Side side) {
      mySide = side;
    }

    @Override
    public void process(@NotNull Handler handler) {
      ThreeSide left = mySide.selectN(ThreeSide.LEFT, ThreeSide.BASE);
      ThreeSide right = mySide.selectN(ThreeSide.BASE, ThreeSide.RIGHT);

      for (SimpleThreesideDiffChange diffChange : myDiffChanges) {
        if (!diffChange.getType().isChange(mySide)) continue;
        if (!handler.process(diffChange.getStartLine(left), diffChange.getEndLine(left),
                             diffChange.getStartLine(right), diffChange.getEndLine(right),
                             diffChange.getDiffType().getColor(myEditors.get(0)))) {
          return;
        }
      }
    }
  }

  private class MyDividerPainter implements DiffSplitter.Painter {
    @NotNull private final Side mySide;
    @NotNull private final MyDividerPaintable myPaintable;

    public MyDividerPainter(@NotNull Side side) {
      mySide = side;
      myPaintable = new MyDividerPaintable(side);
    }

    @Override
    public void paint(@NotNull Graphics g, @NotNull Component divider) {
      Graphics2D gg = getDividerGraphics(g, divider);

      Editor editor1 = mySide.selectN(myEditors.get(0), myEditors.get(1));
      Editor editor2 = mySide.selectN(myEditors.get(1), myEditors.get(2));

      //DividerPolygonUtil.paintSimplePolygons(gg, divider.getWidth(), editor1, editor2, myPaintable);
      DiffDividerDrawUtil.paintPolygons(gg, divider.getWidth(), editor1, editor2, myPaintable);

      myFoldingModel.paintOnDivider(gg, divider, mySide);

      gg.dispose();
    }
  }

  private class MyScrollbarPainter implements ButtonlessScrollBarUI.ScrollbarRepaintCallback {
    @NotNull private final MyDividerPaintable myPaintable = new MyDividerPaintable(Side.RIGHT);

    @Override
    public void call(Graphics g) {
      EditorEx editor1 = myEditors.get(1);
      EditorEx editor2 = myEditors.get(2);

      int width = editor1.getScrollPane().getVerticalScrollBar().getWidth();
      DiffDividerDrawUtil.paintPolygonsOnScrollbar((Graphics2D)g, width, editor1, editor2, myPaintable);

      myFoldingModel.paintOnScrollbar((Graphics2D)g, width);
    }
  }

  private class MyStatusPanel extends StatusPanel {
    @Override
    protected int getChangesCount() {
      return myDiffChanges.size() + myInvalidDiffChanges.size();
    }
  }

  private static class DocumentData {
    @NotNull private final long[] myStamps;
    @NotNull private final CharSequence[] mySequences;

    public DocumentData(@NotNull long[] stamps, @NotNull CharSequence[] sequences) {
      assert stamps.length == 3;
      assert sequences.length == 3;

      myStamps = stamps;
      mySequences = sequences;
    }

    @NotNull
    public long[] getStamps() {
      return myStamps;
    }

    @NotNull
    public CharSequence[] getSequences() {
      return mySequences;
    }
  }
}
