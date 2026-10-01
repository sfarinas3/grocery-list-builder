"""Desktop app: recipe links / pasted text / photos / documents -> grocery list.

The UI end of the pipeline (docs/design.md §3.5). All the real work lives in
`grocery/pipeline.py`; this file only collects inputs, runs the pipeline off
the UI thread, and shows the result as an editable, aisle-grouped checklist —
the human-review step (Quality Check #3) where any miss gets fixed before
shopping.

Run:  uv run python app.py
"""

import re
import sys
import traceback
from pathlib import Path

from PySide6.QtCore import QObject, QRunnable, Qt, QThreadPool, Signal
from PySide6.QtGui import QAction, QBrush, QColor, QKeySequence
from PySide6.QtWidgets import (
    QApplication, QFileDialog, QHBoxLayout, QInputDialog, QLabel, QListWidget,
    QMainWindow, QMenu, QMessageBox, QPlainTextEdit, QPushButton, QSplitter,
    QTabWidget, QTreeWidget, QTreeWidgetItem, QVBoxLayout, QWidget,
)

from grocery import config, export, pipeline
from grocery.extract.llm import LocalLLM
from grocery.models import DEFAULT_CATEGORY, GroceryItem, GroceryList

IMAGE_TYPES = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp"}
DOCUMENT_TYPES = {".pdf", ".docx", ".txt", ".md"}
FLAG_COLOR = QColor("#fff3cd")  # pale amber: "check this row"

# Plain-English versions of the flags the pipeline sets, for row tooltips.
FLAG_TEXT = {
    "needs_category": "No aisle found — pick one (right-click)",
    "mixed_units": "Amounts in different units — see notes",
    "unparsed_name": "Couldn't parse this line — check the name",
    "bad_quantity": "Zero or negative amount",
    "absurd_quantity": "Unusually large amount — likely a misread",
    "empty_name": "No name",
}

# Columns of the grocery list tree.
COL_ITEM, COL_QTY, COL_UNIT, COL_NOTES, COL_SOURCES = range(5)


# --- Background work ---------------------------------------------------------

class _Signals(QObject):
    progress = Signal(str)
    done = Signal(object)
    failed = Signal(str)


class Task(QRunnable):
    """Run `fn(progress)` on the worker thread, reporting back via Qt signals."""

    def __init__(self, fn):
        super().__init__()
        self.fn, self.signals = fn, _Signals()

    def run(self):
        try:
            self.signals.done.emit(self.fn(self.signals.progress.emit))
        except Exception as exc:  # noqa: BLE001 — surface any failure in the UI
            traceback.print_exc()
            self.signals.failed.emit(str(exc) or type(exc).__name__)


# --- Main window -------------------------------------------------------------

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Grocery List Builder")
        self.resize(1200, 760)

        # One worker thread: the model isn't thread-safe, and this also makes a
        # build queue up behind the start-up model load automatically.
        self.pool = QThreadPool(maxThreadCount=1)
        self.llm = LocalLLM()
        self.llm_ok = False
        self.files: list[Path] = []
        self.recipe_links: list[tuple[str, str]] = []

        splitter = QSplitter()
        splitter.addWidget(self._build_inputs())
        splitter.addWidget(self._build_results())
        splitter.setSizes([420, 780])
        self.setCentralWidget(splitter)

        self.model_status = QLabel()
        self.statusBar().addPermanentWidget(self.model_status)
        self._load_model()

    # -- layout --

    def _build_inputs(self) -> QWidget:
        """Left side: the three ways to add recipes, plus the Build button."""
        self.urls = QPlainTextEdit(placeholderText="Paste recipe links, one per line")
        self.pasted = QPlainTextEdit(
            placeholderText="Paste a recipe's text here (handy when a site blocks fetching).\n"
                            "Separate several recipes with a line containing only ---"
        )

        self.file_list = QListWidget()
        add_files = QPushButton("Add photos / documents…", clicked=self._add_files)
        remove_files = QPushButton("Remove selected", clicked=self._remove_files)
        files_tab = QWidget()
        files_layout = QVBoxLayout(files_tab)
        files_layout.addWidget(self.file_list)
        buttons = QHBoxLayout()
        buttons.addWidget(add_files)
        buttons.addWidget(remove_files)
        files_layout.addLayout(buttons)

        tabs = QTabWidget()
        tabs.addTab(self.urls, "Links")
        tabs.addTab(self.pasted, "Paste text")
        tabs.addTab(files_tab, "Photos && documents")

        self.build_button = QPushButton("Build list", clicked=self._build)
        self.build_button.setDefault(True)
        self.build_button.setMinimumHeight(36)

        panel = QWidget()
        layout = QVBoxLayout(panel)
        layout.addWidget(tabs)
        layout.addWidget(self.build_button)
        return panel

    def _build_results(self) -> QWidget:
        """Right side: the editable grocery list and a per-recipe breakdown."""
        self.tree = QTreeWidget()
        self.tree.setHeaderLabels(["Item", "Qty", "Unit", "Notes", "Recipe(s)"])
        for col, width in ((COL_ITEM, 240), (COL_QTY, 50), (COL_UNIT, 90), (COL_NOTES, 260)):
            self.tree.setColumnWidth(col, width)
        self.tree.setContextMenuPolicy(Qt.CustomContextMenu)
        self.tree.customContextMenuRequested.connect(self._item_menu)
        delete = QAction(self.tree, shortcut=QKeySequence.Delete, triggered=self._delete_selected)
        self.tree.addAction(delete)

        self.breakdown = QTreeWidget()
        self.breakdown.setHeaderLabels(["Ingredient (as written)", "Amount", "Shopping name", "Aisle"])
        self.breakdown.setColumnWidth(0, 300)

        tabs = QTabWidget()
        tabs.addTab(self.tree, "Grocery list")
        tabs.addTab(self.breakdown, "By recipe")

        hint = QLabel("Double-click to edit · tick when bought · right-click to change aisle or delete · "
                      "amber rows need a look")
        hint.setStyleSheet("color: gray")
        add_item = QPushButton("Add item", clicked=self._add_item)
        copy = QPushButton("Copy as text", clicked=self._copy)
        save = QPushButton("Save CSV…", clicked=self._save_csv)
        self.email_button = QPushButton("Email…", clicked=self._email)
        if not export.email_configured():
            self.email_button.setEnabled(False)
            self.email_button.setToolTip("Set the GROCERY_SMTP_* environment variables (see config.py)")

        buttons = QHBoxLayout()
        for button in (add_item, copy, save, self.email_button):
            buttons.addWidget(button)
        buttons.addStretch()

        panel = QWidget()
        layout = QVBoxLayout(panel)
        layout.addWidget(tabs)
        layout.addWidget(hint)
        layout.addLayout(buttons)
        return panel

    # -- model --

    def _load_model(self):
        """Download (first run only) and load the model in the background."""
        self.model_status.setText("Preparing model…")
        def load(progress):
            self.llm.load(log=progress)
            # Set here on the worker thread, not in the `done` handler: a build
            # queued during start-up runs on this same thread right after, and
            # would otherwise start before the UI thread has seen the signal.
            self.llm_ok = True

        task = Task(load)
        task.signals.progress.connect(self.model_status.setText)
        task.signals.done.connect(self._model_ready)
        task.signals.failed.connect(self._model_failed)
        self.pool.start(task)

    def _model_ready(self, _):
        self.model_status.setText(f"Model: {config.LLM_FILE}")

    def _model_failed(self, error: str):
        self.model_status.setText("Model unavailable — basic mode")
        self.model_status.setToolTip(error)
        QMessageBox.warning(
            self, "Model unavailable",
            f"The local model couldn't be loaded, so lists will be built with the basic "
            f"(no-model) rules — expect more misses to fix by hand.\n\n{error}",
        )

    # -- inputs --

    def _add_files(self):
        patterns = " ".join(f"*{ext}" for ext in sorted(IMAGE_TYPES | DOCUMENT_TYPES))
        paths, _ = QFileDialog.getOpenFileNames(self, "Add recipes", "", f"Recipes ({patterns})")
        for path in map(Path, paths):
            if path not in self.files:
                self.files.append(path)
                self.file_list.addItem(path.name)

    def _remove_files(self):
        for row in sorted((self.file_list.row(i) for i in self.file_list.selectedItems()), reverse=True):
            self.file_list.takeItem(row)
            del self.files[row]

    def _collect_jobs(self) -> list[tuple[str, callable]]:
        """One (label, fn(llm) -> Recipe) per input, so the worker can loop over them."""
        jobs = []
        urls = dict.fromkeys(re.findall(r"https?://\S+", self.urls.toPlainText()))
        for url in urls:
            jobs.append((url, lambda llm, u=url: pipeline.process_url(u, llm)))
        for text in re.split(r"^\s*---\s*$", self.pasted.toPlainText(), flags=re.M):
            if text.strip():
                title = text.strip().splitlines()[0][:60]  # a recipe's first line is usually its name
                jobs.append((title, lambda llm, t=text, n=title: pipeline.process_text(t, n, llm)))
        for path in self.files:
            process = pipeline.process_image if path.suffix.lower() in IMAGE_TYPES else pipeline.process_document
            jobs.append((path.name, lambda llm, p=path, f=process: f(p.read_bytes(), p.name, llm)))
        return jobs

    # -- build --

    def _build(self):
        jobs = self._collect_jobs()
        if not jobs:
            QMessageBox.information(self, "Nothing to build", "Add a recipe link, some text, or a file first.")
            return
        self.build_button.setEnabled(False)
        self.statusBar().showMessage("Waiting for the model to finish loading…")
        task = Task(lambda progress: self._run_build(jobs, progress))
        task.signals.progress.connect(self.statusBar().showMessage)
        task.signals.done.connect(self._show_results)
        task.signals.failed.connect(self._build_failed)
        self.pool.start(task)

    def _run_build(self, jobs, progress):
        """Worker thread: run every input through the pipeline, then merge."""
        llm = self.llm if self.llm_ok else None
        recipes, errors = [], []
        for n, (label, job) in enumerate(jobs, 1):
            progress(f"Reading recipe {n} of {len(jobs)}: {label}")
            try:
                recipes.append(job(llm))
            except Exception as exc:  # noqa: BLE001 — one bad input shouldn't sink the batch
                errors.append(f"{label}: {exc}")
        progress("Organizing the list…")
        grocery = pipeline.build_list(recipes, llm) if recipes else GroceryList()
        return recipes, grocery, errors

    def _build_failed(self, error: str):
        self.build_button.setEnabled(True)
        self.statusBar().clearMessage()
        QMessageBox.critical(self, "Build failed", error)

    def _show_results(self, result):
        recipes, grocery, errors = result
        self.build_button.setEnabled(True)
        self.recipe_links = [(pipeline.recipe_label(c), c.source_url) for c, _ in recipes if c.source_url]
        self._fill_list(grocery)
        self._fill_breakdown(recipes)
        flagged = sum(1 for it in grocery.items if it.flags)
        self.statusBar().showMessage(
            f"{len(grocery.items)} items from {len(recipes)} recipe(s)"
            + (f" · {flagged} to check" if flagged else "")
        )
        if errors:
            QMessageBox.warning(self, "Some recipes couldn't be read", "\n\n".join(errors))

    # -- grocery list tree --

    def _aisle_node(self, category: str) -> QTreeWidgetItem:
        """The top-level row for an aisle, created (in config order) if missing."""
        for i in range(self.tree.topLevelItemCount()):
            if self.tree.topLevelItem(i).text(0) == category:
                return self.tree.topLevelItem(i)
        node = QTreeWidgetItem([category])
        node.setFlags(Qt.ItemIsEnabled)
        font = node.font(0)
        font.setBold(True)
        node.setFont(0, font)
        order = config.CATEGORIES + [DEFAULT_CATEGORY]
        rank = order.index(category) if category in order else len(order)
        position = sum(
            1 for i in range(self.tree.topLevelItemCount())
            if (order.index(t) if (t := self.tree.topLevelItem(i).text(0)) in order else len(order)) <= rank
        )
        self.tree.insertTopLevelItem(position, node)
        node.setExpanded(True)
        node.setFirstColumnSpanned(True)
        return node

    def _add_row(self, item: GroceryItem):
        row = QTreeWidgetItem([item.name, export.format_quantity(item.quantity), item.unit or "",
                               item.notes, " / ".join(item.sources)])
        row.setFlags(Qt.ItemIsEnabled | Qt.ItemIsSelectable | Qt.ItemIsEditable | Qt.ItemIsUserCheckable)
        row.setCheckState(COL_ITEM, Qt.Checked if item.checked else Qt.Unchecked)
        if item.flags:
            tip = "\n".join(FLAG_TEXT.get(f, f) for f in item.flags)
            for col in range(self.tree.columnCount()):
                row.setBackground(col, QBrush(FLAG_COLOR))
                row.setToolTip(col, tip)
        self._aisle_node(item.category).addChild(row)

    def _fill_list(self, grocery: GroceryList):
        self.tree.clear()
        for item in export.sorted_items(grocery):
            self._add_row(item)

    def current_list(self) -> GroceryList:
        """Read the (possibly edited) tree back into a `GroceryList` for export."""
        items = []
        for i in range(self.tree.topLevelItemCount()):
            node = self.tree.topLevelItem(i)
            for j in range(node.childCount()):
                row = node.child(j)
                try:
                    quantity = float(row.text(COL_QTY)) if row.text(COL_QTY).strip() else None
                except ValueError:
                    quantity = None
                items.append(GroceryItem(
                    name=row.text(COL_ITEM), quantity=quantity, unit=row.text(COL_UNIT) or None,
                    category=node.text(0), notes=row.text(COL_NOTES),
                    checked=row.checkState(COL_ITEM) == Qt.Checked,
                    sources=[s for s in row.text(COL_SOURCES).split(" / ") if s],
                ))
        return GroceryList(items=items)

    def _item_menu(self, pos):
        row = self.tree.itemAt(pos)
        if row is None or row.parent() is None:
            return
        menu = QMenu(self)
        move = menu.addMenu("Move to aisle")
        for category in config.CATEGORIES:
            move.addAction(category, lambda c=category: self._move(row, c))
        menu.addAction("Delete", self._delete_selected)
        menu.exec(self.tree.viewport().mapToGlobal(pos))

    def _move(self, row: QTreeWidgetItem, category: str):
        old = row.parent()
        old.removeChild(row)
        self._aisle_node(category).addChild(row)
        if category in config.CATEGORIES:  # the "needs aisle" warning is resolved
            for col in range(self.tree.columnCount()):
                row.setBackground(col, QBrush())
                row.setToolTip(col, "")
        if old.childCount() == 0:
            self.tree.takeTopLevelItem(self.tree.indexOfTopLevelItem(old))

    def _delete_selected(self):
        for row in self.tree.selectedItems():
            parent = row.parent()
            if parent is None:
                continue
            parent.removeChild(row)
            if parent.childCount() == 0:
                self.tree.takeTopLevelItem(self.tree.indexOfTopLevelItem(parent))

    def _add_item(self):
        self._add_row(GroceryItem(name="new item", category="other"))
        node = self._aisle_node("other")
        row = node.child(node.childCount() - 1)
        self.tree.setCurrentItem(row)
        self.tree.editItem(row, COL_ITEM)

    # -- per-recipe breakdown --

    def _fill_breakdown(self, recipes):
        self.breakdown.clear()
        for content, ingredients in recipes:
            node = QTreeWidgetItem([pipeline.recipe_label(content)])
            node.setFirstColumnSpanned(True)
            self.breakdown.addTopLevelItem(node)
            if not ingredients:
                node.addChild(QTreeWidgetItem(["(no ingredients found — try pasting the text)"]))
            for ing in ingredients:
                amount = f"{export.format_quantity(ing.quantity)} {ing.unit or ''}".strip()
                node.addChild(QTreeWidgetItem([ing.name, amount, ing.shopping_name, ing.category]))
            node.setExpanded(True)

    # -- export --

    def _copy(self):
        QApplication.clipboard().setText(export.as_text(self.current_list(), self.recipe_links))
        self.statusBar().showMessage("Copied to clipboard", 3000)

    def _save_csv(self):
        path, _ = QFileDialog.getSaveFileName(self, "Save list", "grocery-list.csv", "CSV (*.csv)")
        if path:
            Path(path).write_text(export.as_csv(self.current_list(), self.recipe_links), encoding="utf-8")
            self.statusBar().showMessage(f"Saved {path}", 3000)

    def _email(self):
        addrs, ok = QInputDialog.getText(self, "Email list", "Send to (comma-separated):")
        recipients = [a.strip() for a in addrs.split(",") if a.strip()]
        if not ok or not recipients:
            return
        try:
            export.send_email(recipients, export.as_text(self.current_list(), self.recipe_links))
        except Exception as exc:  # noqa: BLE001
            QMessageBox.critical(self, "Email failed", str(exc))
        else:
            self.statusBar().showMessage(f"Sent to {', '.join(recipients)}", 3000)


def main():
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
