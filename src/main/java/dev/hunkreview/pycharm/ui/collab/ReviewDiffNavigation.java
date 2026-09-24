package dev.hunkreview.pycharm.ui.collab;

import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.tools.util.CrossFilePrevNextDifferenceIterableSupport;
import com.intellij.diff.tools.util.DiffDataKeys;
import com.intellij.diff.tools.util.PrevNextFileIterable;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataProvider;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Exposes review files to the native Next Difference action. The IDE declares these interfaces internal to Kotlin. */
public final class ReviewDiffNavigation {
  private ReviewDiffNavigation() {}

  public static void install(
      @NotNull DiffRequestPanel panel,
      @NotNull Supplier<@Nullable String> nextFile,
      @NotNull Consumer<String> selectFile) {
    Navigation navigation = new Navigation(nextFile, selectFile);
    panel.putContextHints(DiffUserDataKeys.DATA_PROVIDER, (DataProvider) dataId -> {
      if (DiffDataKeys.PREV_NEXT_FILE_ITERABLE.is(dataId)
          || DiffDataKeys.CROSS_FILE_PREV_NEXT_DIFFERENCE_ITERABLE.is(dataId)) {
        return navigation;
      }
      return null;
    });
  }

  private static final class Navigation
      implements PrevNextFileIterable, CrossFilePrevNextDifferenceIterableSupport {
    private final Supplier<String> nextFile;
    private final Consumer<String> selectFile;

    private Navigation(Supplier<String> nextFile, Consumer<String> selectFile) {
      this.nextFile = nextFile;
      this.selectFile = selectFile;
    }

    @Override
    public boolean canGoNext(boolean goToFirstChange) {
      return nextFile.get() != null;
    }

    @Override
    public boolean canGoPrev(boolean goToLastChange) {
      return false;
    }

    @Override
    public void goNext(boolean goToFirstChange) {
      String path = nextFile.get();
      if (path != null) selectFile.accept(path);
    }

    @Override
    public void goPrev(boolean goToLastChange) {}

    @Override
    public boolean canGoNextNow() {
      return true;
    }

    @Override
    public boolean canGoPrevNow() {
      return false;
    }

    @Override
    public void prepareGoNext(@NotNull DataContext context) {}

    @Override
    public void prepareGoPrev(@NotNull DataContext context) {}

    @Override
    public void reset() {}
  }
}
