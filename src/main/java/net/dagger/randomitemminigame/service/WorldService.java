package net.dagger.randomitemminigame.service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;

public class WorldService {
	public void setWorldStateForLoading() {
		for (World world : Bukkit.getWorlds()) {
			world.setDifficulty(Difficulty.PEACEFUL);
			world.setTime(0);
			world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
			world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
		}
	}

	public void setSafetyBorder() {
		for (World world : Bukkit.getWorlds()) {
			world.getWorldBorder().setCenter(world.getSpawnLocation());
			world.getWorldBorder().setSize(96); // 48 blocks in all directions = diameter 96
		}
	}

	public void resetBorder() {
		for (World world : Bukkit.getWorlds()) {
			world.getWorldBorder().reset();
		}
	}

	public void setWorldStateActive() {
		for (World world : Bukkit.getWorlds()) {
			world.setDifficulty(Difficulty.NORMAL);
		}
	}

	public void setWorldStateAfterGame() {
		for (World world : Bukkit.getWorlds()) {
			world.setDifficulty(Difficulty.PEACEFUL);
			world.setTime(0);
			world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
			world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
		}
		resetAdvancements();
	}

	private void resetAdvancements() {
		List<Advancement> advancements = new ArrayList<>();
		Iterator<Advancement> iterator = Bukkit.getServer().advancementIterator();
		while (iterator.hasNext()) {
			advancements.add(iterator.next());
		}

		for (Player player : Bukkit.getOnlinePlayers()) {
			for (Advancement advancement : advancements) {
				AdvancementProgress progress = player.getAdvancementProgress(advancement);
				for (String criterion : new ArrayList<>(progress.getAwardedCriteria())) {
					progress.revokeCriteria(criterion);
				}
			}
		}
	}
}
