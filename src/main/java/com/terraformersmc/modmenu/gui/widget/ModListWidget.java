package com.terraformersmc.modmenu.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.config.ModMenuConfig;
import com.terraformersmc.modmenu.gui.ModsScreen;
import com.terraformersmc.modmenu.gui.widget.entries.ChildEntry;
import com.terraformersmc.modmenu.gui.widget.entries.IndependentEntry;
import com.terraformersmc.modmenu.gui.widget.entries.ModListEntry;
import com.terraformersmc.modmenu.gui.widget.entries.ParentEntry;
import com.terraformersmc.modmenu.util.mod.Mod;
import com.terraformersmc.modmenu.util.mod.ModSearch;
import com.terraformersmc.modmenu.util.mod.fabric.FabricIconHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.render.*;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.stream.Collectors;

public class ModListWidget extends AlwaysSelectedEntryListWidget<ModListEntry> implements AutoCloseable {
	public static final boolean DEBUG = Boolean.getBoolean("modmenu.debug");
	private final ModsScreen parent;
	private List<Mod> mods = null;
	private final Set<Mod> addedMods = new HashSet<>();
	private String selectedModId = null;
	private boolean scrolling;
	private final FabricIconHandler iconHandler = new FabricIconHandler();

	public ModListWidget(
		MinecraftClient client,
		int width,
		int height,
		int y,
		int itemHeight,
		ModListWidget list,
		ModsScreen parent
	) {
		super(client, width, height, y, itemHeight);
		this.parent = parent;
		if (list != null) {
			this.mods = list.mods;
		}
	}

	@Override
	public void setScrollAmount(double amount) {
		super.setScrollAmount(amount);
		int denominator = Math.max(0, this.getMaxPosition() - (this.getBottom() - this.getY() - 4));
		if (denominator <= 0) {
			parent.updateScrollPercent(0);
		} else {
			parent.updateScrollPercent(getScrollAmount() / Math.max(
				0,
				this.getMaxPosition() - (this.getBottom() - this.getY() - 4)
			));
		}
	}

	@Override
	public boolean isFocused() {
		return parent.getFocused() == this;
	}

	public void select(ModListEntry entry) {
		this.setSelected(entry);
		if (entry != null) {
			Mod mod = entry.getMod();
			this.client.getNarratorManager()
				.narrate(Text.translatable("narrator.select", mod.getTranslatedName()).getString());
		}
	}

	@Override
	public void setSelected(ModListEntry entry) {
		super.setSelected(entry);
		selectedModId = entry.getMod().getId();
		parent.updateSelectedEntry(getSelectedOrNull());
	}

	@Override
	protected boolean isSelectedEntry(int index) {
		ModListEntry selected = getSelectedOrNull();
		return selected != null && selected.getMod().getId().equals(getEntry(index).getMod().getId());
	}

	@Override
	public int addEntry(ModListEntry entry) {
		if (addedMods.contains(entry.mod)) {
			return 0;
		}
		addedMods.add(entry.mod);
		int i = super.addEntry(entry);
		if (entry.getMod().getId().equals(selectedModId)) {
			setSelected(entry);
		}
		return i;
	}

	@Override
	protected boolean removeEntry(ModListEntry entry) {
		addedMods.remove(entry.mod);
		return super.removeEntry(entry);
	}

	@Override
	protected ModListEntry remove(int index) {
		addedMods.remove(getEntry(index).mod);
		return super.remove(index);
	}

	public void reloadFilters() {
		filter(parent.getSearchInput(), true, false);
	}


	public void filter(String searchTerm, boolean refresh) {
		filter(searchTerm, refresh, true);
	}

	private boolean hasVisibleChildMods(Mod parent) {
		List<Mod> children = ModMenu.PARENT_MAP.get(parent);
		boolean hideLibraries = !ModMenuConfig.SHOW_LIBRARIES.getValue();

		return !children.stream()
			.allMatch(child -> child.isHidden() || hideLibraries && child.getBadges().contains(Mod.Badge.LIBRARY));
	}

	private void filter(String searchTerm, boolean refresh, boolean search) {
		this.clearEntries();
		addedMods.clear();
		Collection<Mod> mods = ModMenu.MODS.values().stream().filter(mod -> {
			if (ModMenuConfig.CONFIG_MODE.getValue()) {
				return !parent.getModHasConfigScreen(mod.getId());
			}

			return !mod.isHidden();
		}).collect(Collectors.toSet());

		if (DEBUG) {
			mods = new ArrayList<>(mods);
			//			mods.addAll(TestModContainer.getTestModContainers());
		}

		if (this.mods == null || refresh) {
			this.mods = new ArrayList<>();
			this.mods.addAll(mods);
			this.mods.sort(ModMenuConfig.SORTING.getValue().getComparator());
		}

		List<Mod> matched = ModSearch.search(parent, searchTerm, this.mods);

		for (Mod mod : matched) {
			String modId = mod.getId();

			//Hide parent lib mods when the config is set to hide
			if (mod.getBadges().contains(Mod.Badge.LIBRARY) && !ModMenuConfig.SHOW_LIBRARIES.getValue()) {
				continue;
			}

			if (!ModMenu.PARENT_MAP.values().contains(mod)) {
				if (ModMenu.PARENT_MAP.keySet().contains(mod) && hasVisibleChildMods(mod)) {
					//Add parent mods when not searching
					List<Mod> children = ModMenu.PARENT_MAP.get(mod);
					children.sort(ModMenuConfig.SORTING.getValue().getComparator());
					ParentEntry parent = new ParentEntry(mod, children, this);
					this.addEntry(parent);
					//Add children if they are meant to be shown
					if (this.parent.showModChildren.contains(modId)) {
						List<Mod> validChildren = ModSearch.search(this.parent, searchTerm, children);
						for (Mod child : validChildren) {
							this.addEntry(new ChildEntry(child,
								parent,
								this,
								validChildren.indexOf(child) == validChildren.size() - 1
							));
						}
					}
				} else {
					//A mod with no children
					this.addEntry(new IndependentEntry(mod, this));
				}
			}
		}

		if (parent.getSelectedEntry() != null && !children().isEmpty() || this.getSelectedOrNull() != null && getSelectedOrNull().getMod() != parent.getSelectedEntry()
			.getMod()) {
			for (ModListEntry entry : children()) {
				if (entry.getMod().equals(parent.getSelectedEntry().getMod())) {
					setSelected(entry);
				}
			}
		} else {
			if (getSelectedOrNull() == null && !children().isEmpty() && getEntry(0) != null) {
				setSelected(getEntry(0));
			}
		}

		if (getScrollAmount() > Math.max(0, this.getMaxPosition() - (this.getBottom() - this.getY() - 4))) {
			setScrollAmount(Math.max(0, this.getMaxPosition() - (this.getBottom() - this.getY() - 4)));
		}
	}


	@Override
	protected void renderList(DrawContext DrawContext, int mouseX, int mouseY, float delta) {
		int entryCount = this.getEntryCount();
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder buffer;

		for (int index = 0; index < entryCount; ++index) {
			int entryTop = this.getRowTop(index) + 2;
			int entryBottom = this.getRowTop(index) + this.itemHeight;
			if (entryBottom >= this.getY() && entryTop <= this.getBottom()) {
				int entryHeight = this.itemHeight - 4;
				ModListEntry entry = this.getEntry(index);
				int rowWidth = this.getRowWidth();
				int entryLeft;
				if (this.isSelectedEntry(index)) {
					entryLeft = getRowLeft() - 2 + entry.getXOffset();
					int selectionRight = this.getRowLeft() + rowWidth + 2;
					float float_2 = this.isFocused() ? 1.0F : 0.5F;
					RenderSystem.setShader(ShaderProgramKeys.POSITION);
					RenderSystem.setShaderColor(float_2, float_2, float_2, 1.0F);
					Matrix4f matrix = DrawContext.getMatrices().peek().getPositionMatrix();
					BuiltBuffer builtBuffer;
					buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
					buffer.vertex(matrix, entryLeft, entryTop + entryHeight + 2, 0.0F);
					buffer.vertex(matrix, selectionRight, entryTop + entryHeight + 2, 0.0F);
					buffer.vertex(matrix, selectionRight, entryTop - 2, 0.0F);
					buffer.vertex(matrix, entryLeft, entryTop - 2, 0.0F);
					try {
						builtBuffer = buffer.end();
						BufferRenderer.drawWithGlobalProgram(builtBuffer);
						builtBuffer.close();
					} catch (Exception e) {
						// Ignored
					}
					RenderSystem.setShader(ShaderProgramKeys.POSITION);
					RenderSystem.setShaderColor(0.0F, 0.0F, 0.0F, 1.0F);
					buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
					buffer.vertex(matrix, entryLeft + 1, entryTop + entryHeight + 1, 0.0F);
					buffer.vertex(matrix, selectionRight - 1, entryTop + entryHeight + 1, 0.0F);
					buffer.vertex(matrix, selectionRight - 1, entryTop - 1, 0.0F);
					buffer.vertex(matrix, entryLeft + 1, entryTop - 1, 0.0F);
					try {
						builtBuffer = buffer.end();
						BufferRenderer.drawWithGlobalProgram(builtBuffer);
						builtBuffer.close();
					} catch (Exception e) {
						// Ignored
					}
				}

				entryLeft = this.getRowLeft();
				entry.render(DrawContext,
					index,
					entryTop,
					entryLeft,
					rowWidth,
					entryHeight,
					mouseX,
					mouseY,
					this.isMouseOver(mouseX, mouseY) && Objects.equals(this.getEntryAtPos(mouseX, mouseY), entry),
					delta
				);
			}
		}
	}

	public void ensureVisible(ModListEntry entry) {
		super.ensureVisible(entry);
	}

	@Override
	protected void updateScrollingState(double double_1, double double_2, int int_1) {
		super.updateScrollingState(double_1, double_2, int_1);
		this.scrolling = int_1 == 0 && double_1 >= (double) this.getScrollbarX() && double_1 < (double) (this.getScrollbarX() + 6);
	}

	@Override
	public boolean mouseClicked(double double_1, double double_2, int int_1) {
		this.updateScrollingState(double_1, double_2, int_1);
		if (!this.isMouseOver(double_1, double_2)) {
			return false;
		} else {
			ModListEntry entry = this.getEntryAtPos(double_1, double_2);
			if (entry != null) {
				if (entry.mouseClicked(double_1, double_2, int_1)) {
					this.setFocused(entry);
					this.setDragging(true);
					return true;
				}
			} else if (int_1 == 0 && this.clickedHeader((int) (double_1 - (double) (this.getX() + this.width / 2 - this.getRowWidth() / 2)),
				(int) (double_2 - (double) this.getY()) + (int) this.getScrollAmount() - 4
			)) {
				return true;
			}

			return this.scrolling;
		}
	}

	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		if (getSelectedOrNull() != null) {
			return getSelectedOrNull().keyPressed(keyCode, scanCode, modifiers);
		}
		return false;
	}

	public final ModListEntry getEntryAtPos(double x, double y) {
		int int_5 = MathHelper.floor(y - (double) this.getY()) - this.headerHeight + (int) this.getScrollAmount() - 4;
		int index = int_5 / this.itemHeight;
		return x < (double) this.getScrollbarX() && x >= (double) getRowLeft() && x <= (double) (getRowLeft() + getRowWidth()) && index >= 0 && int_5 >= 0 && index < this.getEntryCount() ?
			this.children().get(index) :
			null;
	}

	@Override
	protected int getScrollbarX() {
		return this.width - 6;
	}

	@Override
	public int getRowWidth() {
		return this.width - (Math.max(0, this.getMaxPosition() - (this.getBottom() - this.getY() - 4)) > 0 ? 18 : 12);
	}

	@Override
	public int getRowLeft() {
		return this.getX() + 6;
	}

	public int getWidth() {
		return width;
	}

	public int getTop() {
		return this.getY();
	}

	public ModsScreen getParent() {
		return parent;
	}

	@Override
	protected int getMaxPosition() {
		return super.getMaxPosition() + 4;
	}

	public int getDisplayedCountFor(Set<String> set) {
		int count = 0;
		for (ModListEntry c : children()) {
			if (set.contains(c.getMod().getId())) {
				count++;
			}
		}
		return count;
	}

	@Override
	public void close() {
		iconHandler.close();
	}

	public FabricIconHandler getFabricIconHandler() {
		return iconHandler;
	}
}
