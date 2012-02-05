package org.craftmania.world;

import static org.lwjgl.opengl.GL11.GL_LINES;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glColor3f;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glEnd;
import static org.lwjgl.opengl.GL11.glLineWidth;
import static org.lwjgl.opengl.GL11.glVertex3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.craftmania.blocks.Block;
import org.craftmania.datastructures.AABB;
import org.craftmania.datastructures.Fast3DArray;
import org.craftmania.datastructures.ViewFrustum;
import org.craftmania.game.Configuration;
import org.craftmania.game.FontStorage;
import org.craftmania.game.Game;
import org.craftmania.inventory.Inventory;
import org.craftmania.math.MathHelper;
import org.craftmania.rendering.GLFont;
import org.craftmania.utilities.FastArrayList;
import org.craftmania.world.characters.Player;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class World
{
	private WorldProvider _worldProvider;
	private ChunkManager _chunkManager;
	private Player _player;
	private Sky _sky;

	private List<Chunk> _localChunks;
	private List<Chunk> _oldChunkList;

	private FastArrayList<Chunk> _visibleChunks;
	private Inventory _activatedInventory;
	private int _localBlockCount;
	private int _updatingBlocks;
	private long _worldSeed;
	private String _worldName;
	private int _tick;

	public World(String name, long seed) throws Exception
	{
		_worldName = name;
		_worldSeed = seed;
		_worldProvider = new DefaultWorldProvider(this);
		_sky = new Sky();
		_chunkManager = new ChunkManager(this);
		_localChunks = new ArrayList<Chunk>();
		_oldChunkList = new ArrayList<Chunk>();
		_visibleChunks = new FastArrayList<Chunk>(30);
	}

	public void save() throws Exception
	{
		_worldProvider.save();

		/* Make sure the BlockChunkLoader is free. */
		int i = 0;
		do
		{
			++i;
			try
			{
				Thread.sleep(10);
			} catch (Exception e)
			{
			}
		} while (_chunkManager.isBlockChunkThreadingBusy() && i < 300);

		/* Save the local chunks, by destroying them */
		for (Chunk chunk : _localChunks)
		{
			_chunkManager.saveAndUnloadChunk(chunk, false);
		}
		_localChunks.clear();
	}

	public void render()
	{
		/* Prepare Matrixes */
		Game.getInstance().initSceneRendering();

		/* Look through the camera */
		_player.getFirstPersonCamera().lookThrough();

		_sky.render();

		/* Select the visible blocks */
		selectVisibleChunks(_player.getFirstPersonCamera().getViewFrustum());
		// selectVisibleBlocks(_player.getFirstPersonCamera().getViewFrustum());

		for (Chunk ch : _visibleChunks)
		{
			ch.render();
		}
		// for (Block b : _visibleBlocks)
		// {
		// b.render();
		// }

		_player.render();

		if (Game.RENDER_OVERLAY)
			renderOverlay();
	}

	private void renderOverlay()
	{
		Configuration conf = Game.getInstance().getConfiguration();

		Game.getInstance().initOverlayRendering();

		glColor3f(1, 1, 1);
		GLFont infoFont = FontStorage.getFont("Monospaced_20");

		/* Down Left Info */
		infoFont.print(4, 4, "CraftMania");
		infoFont.print(4, 30, _player.coordinatesToString());
		infoFont.print(4, 45, "Visible Chunks:      " + _visibleChunks.size());
		infoFont.print(4, 60, "Updading Blocks:     " + _updatingBlocks);
		infoFont.print(4, 75, "Total Chunks in RAM: " + _chunkManager.getTotalBlockChunkCount());
		infoFont.print(4, 90, "Local Chunks:        " + _localChunks.size());
		infoFont.print(4, 105, "Total Local Blocks:  " + _localBlockCount);

		/** RENDER **/
		if (_activatedInventory != null)
		{
			Game.getInstance().renderTransculentOverlayLayer();
			_activatedInventory.renderInventory();
		} else
		{
			// Center Cross
			{
				int width = conf.getWidth();
				int height = conf.getHeight();
				int crossSize = 10;
				int crossHole = 5;

				glDisable(GL_TEXTURE_2D);
				glLineWidth(2.5f);

				glColor3f(0, 0, 0);
				glBegin(GL_LINES);
				glVertex3f(width / 2 - crossSize - crossHole, height / 2, 0);
				glVertex3f(width / 2 - crossHole, height / 2, 0);

				glVertex3f(width / 2 + crossSize + crossHole, height / 2, 0);
				glVertex3f(width / 2 + crossHole, height / 2, 0);

				glVertex3f(width / 2, height / 2 - crossSize - crossHole, 0);
				glVertex3f(width / 2, height / 2 - crossHole, 0);

				glVertex3f(width / 2, height / 2 + crossSize + crossHole, 0);
				glVertex3f(width / 2, height / 2 + crossHole, 0);

				glEnd();
				glEnable(GL_TEXTURE_2D);
			}
		}
	}

	public void update()
	{
		/*
		 * Do not update the game if it goes very slow, otherwise floats might
		 * become Infinite and NaN
		 */
		if (Game.getInstance().getFPS() < 3)
			return;

		_sky.update();

		while (Keyboard.next())
		{
			if (Keyboard.getEventKey() == Keyboard.KEY_E && Keyboard.getEventKeyState())
			{
				if (_activatedInventory != null)
				{
					setActivatedInventory(null);
				} else
				{
					setActivatedInventory(_player.getInventory());
				}
			} else if (Keyboard.getEventKey() == Keyboard.KEY_F && Keyboard.getEventKeyState())
			{
				_player.toggleFlying();
			} else if (Keyboard.getEventKey() == Keyboard.KEY_C && Keyboard.getEventKeyState())
			{
				for (Chunk c : _localChunks)
				{
					Fast3DArray<Block> blocks = c.getBlocks();
					for (int i = 0; i < blocks.size(); ++i)
					{
						Block b = blocks.getRawObject(i);
						if (b != null)
						{
							b.forceVisiblilityCheck();
							// b.addToVisibilityList();
						}
					}
					c.getVisibleBlocks().updateCacheManagment();
				}
			} else if (Keyboard.getEventKey() == Keyboard.KEY_O && Keyboard.getEventKeyState())
			{
				Game.RENDER_OVERLAY = !Game.RENDER_OVERLAY;
			}
		}
		if (_activatedInventory == null)
		{
			if (!(_localChunks.size() < 4 && _oldChunkList.size() < 4))
			{
				_player.update();
			}
		} else
		{
			_activatedInventory.update();
		}

		selectLocalChunks();
		updateLocalChunks();

		if (_tick % 5 == 0)
		{
			checkForNewVisibleChunks();
		}
		_chunkManager.performRememberedBlockChanges();

		_tick++;
	}

	private void checkForNewVisibleChunks()
	{
		float viewingDistance = Game.getInstance().getConfiguration().getViewingDistance();
		viewingDistance /= Chunk.BLOCKCHUNK_SIZE_HORIZONTAL;
		// viewingDistance += 1.0f;
		int distance = MathHelper.ceil(viewingDistance);
		int distanceSq = distance * distance;

		int centerX = MathHelper.floor(getPlayer().getPosition().x() / Chunk.BLOCKCHUNK_SIZE_HORIZONTAL);
		int centerZ = MathHelper.floor(getPlayer().getPosition().z() / Chunk.BLOCKCHUNK_SIZE_HORIZONTAL);

		ViewFrustum frustum = getPlayer().getFirstPersonCamera().getViewFrustum();

		boolean generate = false;
		int xToGenerate = 0, zToGenerate = 0;

		for (int x = -distance; x < distance; ++x)
		{
			for (int z = -distance; z < distance; ++z)
			{
				int distSq = x * x + z * z;
				if (distSq <= distanceSq)
				{
					if (!generate || (xToGenerate * xToGenerate + zToGenerate * zToGenerate > distSq))
					{
						AABB aabb = null;
						if (distSq > 1)
						{
							aabb = Chunk.createAABBForBlockChunkAt(centerX + x, centerZ + z);
						}
						if (aabb == null || frustum.intersects(aabb))
						{
							Chunk chunk = _chunkManager.getBlockChunk(centerX + x, centerZ + z, false, false, false);
							if (chunk == null || (!chunk.isGenerated() && !chunk.isLoading()))
							{
								generate = true;
								xToGenerate = x;
								zToGenerate = z;
							}
						}
					}
				}
			}
		}
		if (generate)
		{
			System.out.println("New chunk in sight: " + (centerX + xToGenerate) + ", " + (centerZ + zToGenerate));
			Chunk ch = _chunkManager.getBlockChunk(centerX + xToGenerate, centerZ + zToGenerate, true, false, false);
			_chunkManager.loadAndGenerateChunk(ch, true);
		}

	}

	public void setActivatedInventory(Inventory inv)
	{
		_activatedInventory = inv;
		Mouse.setGrabbed(inv == null);
	}

	private void updateLocalChunks()
	{
		_updatingBlocks = 0;
		for (Chunk chunk : _localChunks)
		{
//			synchronized (chunk)
//			{

				for (Iterator<Block> it = chunk.getUpdatingBlocks().iterator(); it.hasNext();)
				{
					Block b = it.next();
					b.update(it);
				}
				_updatingBlocks += chunk.getUpdatingBlocks().size();
				chunk.performListChanges();
//			}
		}
	}

	public void selectLocalChunks()
	{
		_localBlockCount = 0;
		float viewingDistance = Game.getInstance().getConfiguration().getViewingDistance();

		/* Swap the lists */
		List<Chunk> temp = _localChunks;
		_localChunks = _oldChunkList;
		_oldChunkList = temp;

		_chunkManager.getApproximateChunks(_player.getPosition(), viewingDistance + Chunk.BLOCKCHUNK_SIZE_HORIZONTAL, _localChunks);

		if (!_oldChunkList.isEmpty())
		{
			/* Check for chunks getting out of sight to clear the cache */
			outer: for (int i = 0; i < _oldChunkList.size(); ++i)
			{
				Chunk chunkI = _oldChunkList.get(i);
				/* Check if the old chunk is also in the new list */
				for (Chunk chunkJ : _localChunks)
				{

					if (chunkI == chunkJ)
					{
						continue outer;
					}
				}
				if (!chunkI.isDestroying() && !chunkI.isLoading())
				{
					_chunkManager.saveAndUnloadChunk(chunkI, true);
				}
			}
		}

		/* Make sure every local chunk is cached */
		for (Chunk chunk : _localChunks)
		{
			if (chunk.isDestroying() || chunk.isLoading() || !chunk.isLoaded())
				continue;
			chunk.cache();
			_localBlockCount += chunk.getBlockCount();
		}
	}

	private void selectVisibleChunks(ViewFrustum frustum)
	{
		_visibleChunks.clear(false);
		Chunk chunk = null;
		for (int chunkIndex = 0; chunkIndex < _localChunks.size(); ++chunkIndex)
		{
			chunk = _localChunks.get(chunkIndex);
			{
				if (chunk.isEmpty())
					continue;
				if (frustum.intersects(chunk.getAABB()))
				{
					_visibleChunks.add(chunk);
				}
			}
		}
	}

	public Player getPlayer()
	{
		return _player;
	}

	public void setPlayer(Player p)
	{
		_player = p;
	}

	public ChunkManager getChunkManager()
	{
		return _chunkManager;
	}

	public WorldProvider getWorldProvider()
	{
		return _worldProvider;
	}

	public long getWorldSeed()
	{
		return _worldSeed;
	}

	public String getWorldName()
	{
		return _worldName;
	}
}
