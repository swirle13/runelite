/*
 * Copyright (c) 2026, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.plugintroubleshooter;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

@Slf4j
@Singleton
class PluginTroubleshooterPanel extends PluginPanel
{
	private static final String CARD_IDLE = "IDLE";
	private static final String CARD_RUNNING = "RUNNING";
	private static final String CARD_FOUND = "FOUND";
	private static final String CARD_TERMINAL = "TERMINAL";

	private static final Color COLOR_BAD = new Color(0xBE2828);
	private static final Color COLOR_GOOD = new Color(0x1F621F);
	private static final Color COLOR_NEUTRAL = ColorScheme.MEDIUM_GRAY_COLOR;

	private final PluginManager pluginManager;
	private final EventBus eventBus;

	private final CardLayout cardLayout;
	private final JPanel cardContainer;

	// Mutable panels that are rebuilt per-session / per-step
	private JPanel idleCard;
	private JPanel runningCard;
	private JPanel foundCard;
	private JPanel terminalCard;

	private TroubleshooterSession session;

	@Inject
	PluginTroubleshooterPanel(PluginManager pluginManager, EventBus eventBus)
	{
		super(false);

		this.pluginManager = pluginManager;
		this.eventBus = eventBus;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		cardLayout = new CardLayout();
		cardContainer = new JPanel(cardLayout);
		cardContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(cardContainer, BorderLayout.CENTER);

		buildIdleCard();
		showCard(CARD_IDLE);
	}

	@Override
	public void onActivate()
	{
		eventBus.register(this);

		if (session == null)
		{
			rebuildIdleCard();
			showCard(CARD_IDLE);
		}
	}

	@Override
	public void onDeactivate()
	{
		eventBus.unregister(this);
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		if (session != null)
		{
			// A profile change refreshes plugin states from config, invalidating our bisect.
			cancelSession();
		}
	}

	// ---- Session lifecycle ----

	private void startSession()
	{
		List<Plugin> candidates = collectCandidates();

		Map<Plugin, Boolean> snapshot = new LinkedHashMap<>();
		for (Plugin plugin : pluginManager.getPlugins())
		{
			snapshot.put(plugin, pluginManager.isPluginEnabled(plugin));
		}

		session = new TroubleshooterSession(candidates, snapshot);

		if (session.getState() == TroubleshooterState.NOT_FOUND)
		{
			buildTerminalCard("No plugins to troubleshoot",
				"There are no active, user-toggleable plugins to test.");
			showCard(CARD_TERMINAL);
			session = null;
			return;
		}

		applySessionStep();
		buildRunningCard();
		showCard(CARD_RUNNING);
	}

	private void advanceBad()
	{
		session.reportBad();
		afterAdvance();
	}

	private void advanceGood()
	{
		session.reportGood();
		afterAdvance();
	}

	private void afterAdvance()
	{
		switch (session.getState())
		{
			case FOUND:
				restoreExcept(session.getResult());
				buildFoundCard();
				showCard(CARD_FOUND);
				break;
			case NOT_FOUND:
				restoreAll();
				buildTerminalCard("Could not identify the problem",
					"<html>The troubleshooter could not narrow it down to a"
						+ " single plugin. This can happen when multiple plugins"
						+ " interact to cause the issue.<br><br>"
						+ "Try running the troubleshooter again, or ask for help"
						+ " on the RuneLite Discord.</html>");
				showCard(CARD_TERMINAL);
				clearSession();
				break;
			default:
				applySessionStep();
				buildRunningCard();
				showCard(CARD_RUNNING);
				break;
		}
	}

	private void cancelSession()
	{
		if (session != null)
		{
			session.cancel();
			restoreAll();
		}
		clearSession();
		rebuildIdleCard();
		showCard(CARD_IDLE);
	}

	private void finishKeepDisabled()
	{
		// Already restored-except in FOUND transition; just reset the session.
		clearSession();
		rebuildIdleCard();
		showCard(CARD_IDLE);
	}

	private void finishReenableAll()
	{
		restoreAll();
		clearSession();
		rebuildIdleCard();
		showCard(CARD_IDLE);
	}

	private void clearSession()
	{
		session = null;
	}

	// ---- Plugin state management ----

	private List<Plugin> collectCandidates()
	{
		Set<Class<? extends Plugin>> dependencyRoots = pluginManager.getPlugins().stream()
			.flatMap(p -> Arrays.stream(p.getClass().getAnnotationsByType(PluginDependency.class))
				.map(PluginDependency::value))
			.collect(Collectors.toSet());

		return pluginManager.getPlugins().stream()
			.filter(pluginManager::isPluginActive)
			.filter(p -> !p.getClass().getAnnotation(PluginDescriptor.class).hidden())
			.filter(p -> !dependencyRoots.contains(p.getClass()))
			.collect(Collectors.toList());
	}

	private void applySessionStep()
	{
		Set<Plugin> enabled = new HashSet<>(session.getEnabledHalf());

		for (Plugin plugin : session.getSuspects())
		{
			boolean wantEnabled = enabled.contains(plugin);
			boolean isActive = pluginManager.isPluginActive(plugin);

			if (wantEnabled == isActive)
			{
				continue;
			}

			setPluginState(plugin, wantEnabled);
		}
	}

	private void restoreAll()
	{
		if (session == null)
		{
			return;
		}

		for (Map.Entry<Plugin, Boolean> entry : session.getOriginalStates().entrySet())
		{
			boolean wasEnabled = entry.getValue();
			boolean isActive = pluginManager.isPluginActive(entry.getKey());

			if (wasEnabled == isActive)
			{
				continue;
			}

			setPluginState(entry.getKey(), wasEnabled);
		}
	}

	private void restoreExcept(Plugin exclude)
	{
		if (session == null)
		{
			return;
		}

		for (Map.Entry<Plugin, Boolean> entry : session.getOriginalStates().entrySet())
		{
			if (entry.getKey() == exclude)
			{
				setPluginState(exclude, false);
				continue;
			}

			boolean wasEnabled = entry.getValue();
			boolean isActive = pluginManager.isPluginActive(entry.getKey());

			if (wasEnabled == isActive)
			{
				continue;
			}

			setPluginState(entry.getKey(), wasEnabled);
		}
	}

	private void setPluginState(Plugin plugin, boolean enabled)
	{
		pluginManager.setPluginEnabled(plugin, enabled);
		try
		{
			if (enabled)
			{
				pluginManager.startPlugin(plugin);
			}
			else
			{
				pluginManager.stopPlugin(plugin);
			}
		}
		catch (PluginInstantiationException e)
		{
			log.warn("Failed to {} plugin {} during troubleshoot",
				enabled ? "start" : "stop",
				plugin.getClass().getSimpleName(), e);
		}
	}

	// ---- Card builders ----

	private void showCard(String card)
	{
		cardLayout.show(cardContainer, card);
		cardContainer.revalidate();
		cardContainer.repaint();
	}

	private void buildIdleCard()
	{
		idleCard = createBasePanel();
		rebuildIdleContent(idleCard);
		cardContainer.add(idleCard, CARD_IDLE);
	}

	private void rebuildIdleCard()
	{
		idleCard.removeAll();
		rebuildIdleContent(idleCard);
		idleCard.revalidate();
		idleCard.repaint();
	}

	private void rebuildIdleContent(JPanel panel)
	{
		JLabel title = createTitle("Plugin Troubleshooter");
		panel.add(title);
		panel.add(Box.createVerticalStrut(12));

		JLabel description = createWrappedLabel(
			"<html>Having a problem? This tool helps you quickly"
				+ " find which plugin is causing it.<br><br>"
				+ "<b>How it works:</b><br>"
				+ "1. Some plugins will be disabled<br>"
				+ "2. You tell us if the issue persists<br>"
				+ "3. We repeat until we find it</html>");
		panel.add(description);
		panel.add(Box.createVerticalStrut(16));

		long activeCount = pluginManager.getPlugins().stream()
			.filter(pluginManager::isPluginActive)
			.filter(p -> !p.getClass().getAnnotation(PluginDescriptor.class).hidden())
			.count();

		int estimatedSteps = TroubleshooterSession.computeTotalSteps((int) activeCount);

		JLabel stats = createWrappedLabel(
			"<html><b>" + activeCount + "</b> plugins are currently active.<br>"
				+ "This should take roughly <b>" + estimatedSteps + "</b> steps.</html>");
		stats.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(stats);
		panel.add(Box.createVerticalStrut(20));

		JButton startButton = createButton("Start Troubleshooting", COLOR_GOOD);
		startButton.addActionListener(e -> startSession());
		startButton.setEnabled(activeCount > 0);
		panel.add(startButton);
	}

	private void buildRunningCard()
	{
		if (runningCard != null)
		{
			cardContainer.remove(runningCard);
		}

		runningCard = createBasePanel();

		JLabel stepLabel = createTitle("Step " + session.getStep() + " of " + session.getTotalSteps());
		runningCard.add(stepLabel);
		runningCard.add(Box.createVerticalStrut(12));

		List<Plugin> disabled = session.getDisabledHalf();
		JLabel info = createWrappedLabel(
			"<html><b>" + disabled.size() + "</b> plugin"
				+ (disabled.size() != 1 ? "s have" : " has")
				+ " been temporarily disabled for this test.<br><br>"
				+ "Try to reproduce the issue now.</html>");
		runningCard.add(info);
		runningCard.add(Box.createVerticalStrut(12));

		JLabel prompt = new JLabel("Does the problem still occur?");
		prompt.setForeground(Color.WHITE);
		prompt.setFont(FontManager.getRunescapeBoldFont());
		prompt.setAlignmentX(LEFT_ALIGNMENT);
		runningCard.add(prompt);
		runningCard.add(Box.createVerticalStrut(10));

		JButton badButton = createButton("Yes, still broken", COLOR_BAD);
		badButton.addActionListener(e -> advanceBad());
		runningCard.add(badButton);
		runningCard.add(Box.createVerticalStrut(6));

		JButton goodButton = createButton("No, it's fixed now", COLOR_GOOD);
		goodButton.addActionListener(e -> advanceGood());
		runningCard.add(goodButton);
		runningCard.add(Box.createVerticalStrut(16));

		JButton cancelButton = createButton("Cancel", COLOR_NEUTRAL);
		cancelButton.addActionListener(e -> cancelSession());
		runningCard.add(cancelButton);
		runningCard.add(Box.createVerticalStrut(16));

		// Collapsible list of disabled plugins
		JLabel disabledHeader = new JLabel("Disabled plugins:");
		disabledHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		disabledHeader.setFont(FontManager.getRunescapeSmallFont());
		disabledHeader.setAlignmentX(LEFT_ALIGNMENT);
		runningCard.add(disabledHeader);
		runningCard.add(Box.createVerticalStrut(4));

		JPanel disabledList = new JPanel();
		disabledList.setLayout(new BoxLayout(disabledList, BoxLayout.Y_AXIS));
		disabledList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		disabledList.setBorder(new EmptyBorder(6, 8, 6, 8));
		disabledList.setAlignmentX(LEFT_ALIGNMENT);

		for (Plugin p : disabled)
		{
			JLabel pluginLabel = new JLabel("\u2022 " + p.getName());
			pluginLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			pluginLabel.setFont(FontManager.getRunescapeSmallFont());
			disabledList.add(pluginLabel);
		}

		runningCard.add(disabledList);

		cardContainer.add(runningCard, CARD_RUNNING);
	}

	private void buildFoundCard()
	{
		if (foundCard != null)
		{
			cardContainer.remove(foundCard);
		}

		foundCard = createBasePanel();
		Plugin result = session.getResult();

		JLabel title = createTitle("Found the problem!");
		title.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
		foundCard.add(title);
		foundCard.add(Box.createVerticalStrut(12));

		JLabel resultLabel = createWrappedLabel(
			"<html><b>\"" + result.getName() + "\"</b> is likely"
				+ " causing your issue.</html>");
		foundCard.add(resultLabel);
		foundCard.add(Box.createVerticalStrut(4));

		String pluginSource = ExternalPluginManager.getInternalName(result.getClass()) != null
			? "Plugin Hub plugin"
			: "Core plugin";
		JLabel sourceLabel = new JLabel("(" + pluginSource + ")");
		sourceLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sourceLabel.setFont(FontManager.getRunescapeSmallFont());
		sourceLabel.setAlignmentX(LEFT_ALIGNMENT);
		foundCard.add(sourceLabel);
		foundCard.add(Box.createVerticalStrut(20));

		JButton keepDisabled = createButton("Keep it disabled", COLOR_BAD);
		keepDisabled.addActionListener(e -> finishKeepDisabled());
		foundCard.add(keepDisabled);
		foundCard.add(Box.createVerticalStrut(6));

		JButton reenableAll = createButton("Re-enable all plugins", COLOR_GOOD);
		reenableAll.addActionListener(e -> finishReenableAll());
		foundCard.add(reenableAll);
		foundCard.add(Box.createVerticalStrut(6));

		JButton reportBug = createButton("Report a bug", COLOR_NEUTRAL);
		reportBug.addActionListener(e -> openSupportPage(result));
		foundCard.add(reportBug);

		cardContainer.add(foundCard, CARD_FOUND);
	}

	private void buildTerminalCard(String headerText, String bodyText)
	{
		if (terminalCard != null)
		{
			cardContainer.remove(terminalCard);
		}

		terminalCard = createBasePanel();

		JLabel title = createTitle(headerText);
		title.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		terminalCard.add(title);
		terminalCard.add(Box.createVerticalStrut(12));

		JLabel body = createWrappedLabel(bodyText);
		terminalCard.add(body);
		terminalCard.add(Box.createVerticalStrut(20));

		JButton restoreButton = createButton("Restore all plugins", COLOR_GOOD);
		restoreButton.addActionListener(e ->
		{
			restoreAll();
			clearSession();
			rebuildIdleCard();
			showCard(CARD_IDLE);
		});
		terminalCard.add(restoreButton);
		terminalCard.add(Box.createVerticalStrut(6));

		JButton backButton = createButton("Back to start", COLOR_NEUTRAL);
		backButton.addActionListener(e ->
		{
			clearSession();
			rebuildIdleCard();
			showCard(CARD_IDLE);
		});
		terminalCard.add(backButton);

		cardContainer.add(terminalCard, CARD_TERMINAL);
	}

	// ---- Support link ----

	private static void openSupportPage(Plugin plugin)
	{
		String internalName = ExternalPluginManager.getInternalName(plugin.getClass());
		if (internalName != null)
		{
			LinkBrowser.browse("https://runelite.net/plugin-hub/show/" + internalName);
		}
		else
		{
			LinkBrowser.browse("https://github.com/runelite/runelite/wiki/"
				+ plugin.getName().replace(' ', '-'));
		}
	}

	// ---- UI helpers ----

	private static JPanel createBasePanel()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(10, 10, 10, 10));
		return panel;
	}

	private static JLabel createTitle(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	private static JLabel createWrappedLabel(String html)
	{
		String wrapped = html.startsWith("<html>") ? html : "<html>" + html + "</html>";
		JLabel label = new JLabel(wrapped);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	private static JButton createButton(String text, Color background)
	{
		JButton button = new JButton(text);
		button.setAlignmentX(LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		button.setFocusPainted(false);
		button.setBackground(background);
		button.setForeground(Color.WHITE);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(background.darker(), 1),
			new EmptyBorder(4, 8, 4, 8)));
		return button;
	}
}





