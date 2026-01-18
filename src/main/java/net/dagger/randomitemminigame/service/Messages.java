package net.dagger.randomitemminigame.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class Messages {
	public static Component get(LanguageService.Language lang, MessageKey key, Object... args) {
		String message = getStringPrivate(lang, key, args);
		NamedTextColor color = getColor(key);
		return Component.text(message, color);
	}

	public static Component get(LanguageService.Language lang, MessageKey key, NamedTextColor color, Object... args) {
		String message = getStringPrivate(lang, key, args);
		return Component.text(message, color);
	}

	public static String getString(LanguageService.Language lang, MessageKey key, Object... args) {
		String template;
		switch (lang) {
			case EN:
				template = key.english;
				break;
			case UK:
				template = key.ukrainian;
				break;
			case RU:
			default:
				template = key.russian;
				break;
		}
		return String.format(template, args);
	}

	private static String getStringPrivate(LanguageService.Language lang, MessageKey key, Object... args) {
		return getString(lang, key, args);
	}

	private static NamedTextColor getColor(MessageKey key) {
		return key.defaultColor;
	}

	public enum MessageKey {
		USAGE("/lootrush <start|stop|status|role|cancel|skip|lang>", "/lootrush <start|stop|status|role|cancel|skip|lang>", "/lootrush <start|stop|status|role|cancel|skip|lang>", NamedTextColor.YELLOW),
		UNKNOWN_SUBCOMMAND("Неизвестная подкоманда. Используйте: start, stop, status, role, cancel, skip или lang.", "Unknown subcommand. Use: start, stop, status, role, cancel, skip or lang.", "Невідома підкоманда. Використовуйте: start, stop, status, role, cancel, skip або lang.", NamedTextColor.RED),
		GAME_ALREADY_RUNNING("Мини-игра уже запущена или идёт отсчёт.", "The minigame is already running or countdown is in progress.", "Міні-гру вже запущено або триває відлік.", NamedTextColor.RED),
		NO_PLAYERS("Нет игроков, участвующих в мини-игре. Используйте /lootrush role player.", "No players participating in the minigame. Use /lootrush role player.", "Немає гравців, які беруть участь у міні-грі. Використовуйте /lootrush role player.", NamedTextColor.RED),
		RANDOM_ITEM_HEADER("=== Случайный предмет ===", "=== Random Item ===", "=== Випадковий предмет ===", NamedTextColor.GOLD),
		NEED_TO_OBTAIN("Нужно первым добыть: ", "You need to obtain first: ", "Потрібно першим добути: ", NamedTextColor.YELLOW),
		PLAYERS_TELEPORTED("Игроки телепортированы. Отсчёт %d сек...", "Players teleported. Countdown %d sec...", "Гравців телепортовано. Відлік %d сек...", NamedTextColor.GRAY),
		GAME_ALREADY_STOPPED("Мини-игра и так остановлена.", "The minigame is already stopped.", "Міні-гру і так зупинено.", NamedTextColor.RED),
		GAME_STOPPED("Мини-игра остановлена администратором.", "The minigame was stopped by an administrator.", "Міні-гру зупинено адміністратором.", NamedTextColor.RED),
		NO_COUNTDOWN("Нет активного отсчёта или загрузки для прерывания. Используйте /lootrush stop для остановки игры.", "No active countdown or loading to interrupt. Use /lootrush stop to stop the game.", "Немає активного відліку або завантаження для переривання. Використовуйте /lootrush stop для зупинки гри.", NamedTextColor.RED),
		GAME_CANCELLED("Начало игры прервано администратором.", "Game start was cancelled by an administrator.", "Початок гри перервано адміністратором.", NamedTextColor.RED),
		GAME_NOT_ACTIVE("Игра не активна. Пропуск предмета возможен только во время активной игры.", "Game is not active. Skipping item is only possible during an active game.", "Гра не активна. Пропуск предмета можливий тільки під час активної гри.", NamedTextColor.RED),
		NO_CURRENT_ITEM("Нет текущего предмета для пропуска.", "No current item to skip.", "Немає поточного предмета для пропуску.", NamedTextColor.RED),
		ITEM_SKIPPED("Предмет пропущен администратором. ", "Item skipped by administrator. ", "Предмет пропущено адміністратором. ", NamedTextColor.YELLOW),
		NEW_ITEM("Новый предмет: ", "New item: ", "Новий предмет: ", NamedTextColor.GOLD),
		ITEM_CHANGED("Предмет изменён с ", "Item changed from ", "Предмет змінено з ", NamedTextColor.GREEN),
		ITEM_CHANGED_TO(" на ", " to ", " на ", NamedTextColor.GREEN),
		GAME_NOT_STARTED("Мини-игра не запущена.", "The minigame is not started.", "Міні-гру не запущено.", NamedTextColor.GREEN),
		COUNTDOWN_IN_PROGRESS("Идёт отсчёт. Цель: ", "Countdown in progress. Target: ", "Триває відлік. Ціль: ", NamedTextColor.YELLOW),
		GAME_ACTIVE("Игра активна! Цель: ", "Game active! Target: ", "Гра активна! Ціль: ", NamedTextColor.AQUA),
		NO_PERMISSION_ROLE("Недостаточно прав для изменения ролей.", "Insufficient permissions to change roles.", "Недостатньо прав для зміни ролей.", NamedTextColor.RED),
		ROLE_USAGE("Использование: /lootrush role <player|spectator> [ник|селектор]", "Usage: /lootrush role <player|spectator> [player|selector]", "Використання: /lootrush role <player|spectator> [нік|селектор]", NamedTextColor.YELLOW),
		UNKNOWN_ROLE("Неизвестная роль. Доступно: player, spectator.", "Unknown role. Available: player, spectator.", "Невідома роль. Доступно: player, spectator.", NamedTextColor.RED),
		SELECTOR_NO_PLAYERS("Селектор %s не нашёл игроков.", "Selector %s found no players.", "Селектор %s не знайшов гравців.", NamedTextColor.RED),
		INVALID_SELECTOR("Неверный селектор: %s", "Invalid selector: %s", "Невірний селектор: %s", NamedTextColor.RED),
		PLAYER_NOT_FOUND("Игрок %s не найден.", "Player %s not found.", "Гравця %s не знайдено.", NamedTextColor.RED),
		NEED_PLAYER_OR_SELECTOR("Нужно указать ник игрока или селектор.", "You need to specify a player name or selector.", "Потрібно вказати нік гравця або селектор.", NamedTextColor.YELLOW),
		ROLE_SET_BY_ADMIN("Администратор установил вам роль %s.", "An administrator set your role to %s.", "Адміністратор встановив вам роль %s.", NamedTextColor.AQUA),
		ROLE_SET_SINGLE("Установлена роль %s для %s.", "Role %s set for %s.", "Встановлено роль %s для %s.", NamedTextColor.GREEN),
		ROLE_SET_MULTIPLE("Установлена роль %s для %d игроков.", "Role %s set for %d players.", "Встановлено роль %s для %d гравців.", NamedTextColor.GREEN),
		LANG_USAGE("Использование: /lootrush lang <ru|en|ua>", "Usage: /lootrush lang <ru|en|ua>", "Використання: /lootrush lang <ru|en|ua>", NamedTextColor.YELLOW),
		UNKNOWN_LANGUAGE("Неизвестный язык. Доступно: ru, en, ua.", "Unknown language. Available: ru, en, ua.", "Невідома мова. Доступно: ru, en, ua.", NamedTextColor.RED),
		LANGUAGE_SET("Язык изменён на %s.", "Language changed to %s.", "Мову змінено на %s.", NamedTextColor.GREEN),
		CURRENT_LANGUAGE("Текущий язык: %s", "Current language: %s", "Поточна мова: %s", NamedTextColor.AQUA),
		NOW_PARTICIPATING("Теперь вы участвуете в мини-игре.", "You are now participating in the minigame.", "Тепер ви берете участь у міні-грі.", NamedTextColor.GREEN),
		NOW_SPECTATOR("Вы перешли в режим наблюдателя.", "You switched to spectator mode.", "Ви перейшли в режим спостерігача.", NamedTextColor.AQUA),
		SPECTATING_ROUND("Вы наблюдаете за раундом как зритель.", "You are spectating the round as a viewer.", "Ви спостерігаєте за раундом як глядач.", NamedTextColor.GRAY),
		LOADING_CHUNKS("Загружаем чанки для телепортации...", "Loading chunks for teleportation...", "Завантажуємо чанки для телепортації...", NamedTextColor.YELLOW),
		TELEPORTED_TO("Вы телепортированы в %s", "You have been teleported to %s", "Ви телепортовані в %s", NamedTextColor.GRAY),
		SEARCHING_LOCATION("Ищем место для %s...", "Searching location for %s...", "Шукаємо місце для %s...", NamedTextColor.YELLOW),
		LOCATION_FOUND("Нашли место для %s: %s", "Found location for %s: %s", "Знайшли місце для %s: %s", NamedTextColor.GREEN),
		ATTEMPT_TOO_CLOSE("Попытка #%d для %s: (%d, ???, %d) слишком близко к другим игрокам", "Attempt #%d for %s: (%d, ???, %d) too close to other players", "Спроба #%d для %s: (%d, ???, %d) занадто близько до інших гравців", NamedTextColor.GRAY),
		ATTEMPT_Y_TOO_LOW("Попытка #%d для %s: (%d, %d) отклонена — Y ниже минимума", "Attempt #%d for %s: (%d, %d) rejected — Y below minimum", "Спроба #%d для %s: (%d, %d) відхилена — Y нижче мінімуму", NamedTextColor.GRAY),
		ATTEMPT_LOCATION_FOUND("Попытка #%d для %s: найдена точка (%d, %d, %d)", "Attempt #%d for %s: found location (%d, %d, %d)", "Спроба #%d для %s: знайдена точка (%d, %d, %d)", NamedTextColor.GREEN),
		ATTEMPT_UNSAFE_BLOCKS("Попытка #%d для %s: (%d, %d, %d) отклонена — блоки небезопасны (floor=%s, feet=%s, head=%s)", "Attempt #%d for %s: (%d, %d, %d) rejected — blocks unsafe (floor=%s, feet=%s, head=%s)", "Спроба #%d для %s: (%d, %d, %d) відхилена — блоки небезпечні (floor=%s, feet=%s, head=%s)", NamedTextColor.GRAY),
		LOADING_NEAR_CHUNKS("Загружаем ближние чанки: ", "Loading nearby chunks: ", "Завантажуємо ближні чанки: ", NamedTextColor.YELLOW),
		CHUNKS_TEXT(" чанков...", " chunks...", " чанків...", NamedTextColor.YELLOW),
		CHUNK_LOADED("Загружен чанк ", "Loaded chunk ", "Завантажено чанк ", NamedTextColor.GRAY),
		CHUNK_LOADED_WITH_COORDS("Загружен чанк [%d, %d] (%d/%d)", "Loaded chunk [%d, %d] (%d/%d)", "Завантажено чанк [%d, %d] (%d/%d)", NamedTextColor.GRAY),
		NEAR_CHUNKS_LOADED("Ближние чанки загружены! ", "Nearby chunks loaded! ", "Ближні чанки завантажені! ", NamedTextColor.GREEN),
		LOADING_FAR_CHUNKS("Загружаем дальние чанки: ", "Loading distant chunks: ", "Завантажуємо дальні чанки: ", NamedTextColor.YELLOW),
		ALL_CHUNKS_LOADED("Все чанки загружены! ", "All chunks loaded! ", "Всі чанки завантажені! ", NamedTextColor.GREEN),
		CHUNKS_COUNT(" (%d чанков)", " (%d chunks)", " (%d чанків)", NamedTextColor.GRAY),
		CHUNK_LOAD_ERROR("Ошибка при прогрузке чанка: %s", "Error loading chunk: %s", "Помилка при завантаженні чанка: %s", NamedTextColor.RED),
		SCATTER_BOSS_BAR("Поиск безопасных локаций... %d/%d", "Searching safe locations... %d/%d", "Пошук безпечних локацій... %d/%d", NamedTextColor.BLUE),
		TELEPORTATION_COMPLETE("Телепортация завершена", "Teleportation complete", "Телепортація завершена", NamedTextColor.GREEN),
		TELEPORTATION_STOPPED("Телепортация остановлена", "Teleportation stopped", "Телепортація зупинена", NamedTextColor.RED),
		SWAP_IN_SECONDS("Случайная смена мест через %d секунд!", "Random location swap in %d seconds!", "Випадкова зміна місць через %d секунд!", NamedTextColor.LIGHT_PURPLE),
		SWAP_IN_SECONDS_SHORT("Смена мест через %d секунд.", "Location swap in %d seconds.", "Зміна місць через %d секунд.", NamedTextColor.LIGHT_PURPLE),
		SWAP_IN_MINUTE("Случайная смена мест через 1 минуту!", "Random location swap in 1 minute!", "Випадкова зміна місць через 1 хвилину!", NamedTextColor.YELLOW),
		SWAP_IN_30_SECONDS("Случайная смена мест через 30 секунд!", "Random location swap in 30 seconds!", "Випадкова зміна місць через 30 секунд!", NamedTextColor.YELLOW),
		SWAP_STARTING("Случайная смена мест начнётся через %d секунд!", "Random location swap will start in %d seconds!", "Випадкова зміна місць почнеться через %d секунд!", NamedTextColor.LIGHT_PURPLE),
		PLAYERS_SWAPPING("Игроки меняются местами!", "Players are swapping locations!", "Гравці міняються місцями!", NamedTextColor.LIGHT_PURPLE),
		LIVES("Жизни", "Lives", "Життя", NamedTextColor.RED),
		TIME("Время: ", "Time: ", "Час: ", NamedTextColor.GOLD),
		START_GOOD_LUCK("Старт! Удачи в поисках ", "Start! Good luck finding ", "Старт! Удачі в пошуках ", NamedTextColor.GREEN),
		START_IN_SECONDS("Старт через %d...", "Start in %d...", "Старт через %d...", NamedTextColor.YELLOW),
		NO_LIVES_LEFT("Вы исчерпали все жизни и теперь наблюдаете за раундом.", "You have run out of lives and are now spectating the round.", "Ви вичерпали всі життя і тепер спостерігаєте за раундом.", NamedTextColor.RED),
		PLAYER_OUT("%s исчерпал все жизни и выбыл из игры.", "%s has run out of lives and is out of the game.", "%s вичерпав всі життя і вибув з гри.", NamedTextColor.GRAY),
		LIVES_REMAINING("У вас осталось жизней: %d из %d", "You have %d lives remaining out of %d", "У вас залишилося життів: %d з %d", NamedTextColor.YELLOW),
		PLAYER_WON("Игрок ", "Player ", "Гравець ", NamedTextColor.GOLD),
		OBTAINED_FIRST(" первым добыл ", " obtained first ", " першим добув ", NamedTextColor.GOLD),
		AND_WON(" и победил!", " and won!", " і переміг!", NamedTextColor.GOLD),
		LAST_PLAYER_STANDING("Игрок остался один в живых: ", "Last player standing: ", "Гравець залишився один в живих: ", NamedTextColor.GOLD),
		WINS_ROUND(" и выигрывает раунд!", " wins the round!", " і виграє раунд!", NamedTextColor.GOLD),
		RETURNING_TO_SPAWN("Возвращаем на спавн и очищаем инвентарь после раунда.", "Returning to spawn and clearing inventory after the round.", "Повертаємо на спавн і очищаємо інвентар після раунду.", NamedTextColor.GRAY),
		BOSS_BAR_TARGET_ITEM("Найти: ", "Find: ", "Знайти: ", NamedTextColor.WHITE);

		private final String russian;
		private final String english;
		private final String ukrainian;
		private final NamedTextColor defaultColor;

		MessageKey(String russian, String english, String ukrainian, NamedTextColor defaultColor) {
			this.russian = russian;
			this.english = english;
			this.ukrainian = ukrainian;
			this.defaultColor = defaultColor;
		}
	}
}
