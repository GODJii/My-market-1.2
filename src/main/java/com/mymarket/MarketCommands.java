package com.mymarket;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class MarketCommands {
	private MarketCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext ctx,
								Commands.CommandSelection env) {
		d.register(Commands.literal("sell").executes(c -> {
			SellMenu.open(c.getSource().getPlayerOrException());
			return 1;
		}));

		for (String name : new String[]{"shop", "buy"}) {
			d.register(Commands.literal(name).executes(c -> {
				BuyMenu.open(c.getSource().getPlayerOrException());
				return 1;
			}));
		}

		for (String name : new String[]{"money", "balance"}) {
			d.register(Commands.literal(name)
					.executes(MarketCommands::showBalance)
					.then(Commands.literal("give").requires(s -> s.hasPermission(2))
							.then(Commands.argument("player", EntityArgument.player())
									.then(Commands.argument("amount", LongArgumentType.longArg(1))
											.executes(c -> change(c, true)))))
					.then(Commands.literal("take").requires(s -> s.hasPermission(2))
							.then(Commands.argument("player", EntityArgument.player())
									.then(Commands.argument("amount", LongArgumentType.longArg(1))
											.executes(c -> change(c, false))))));
		}

		d.register(Commands.literal("price").executes(MarketCommands::showPrice));
	}

	private static int showBalance(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		long money = MoneyData.get(c.getSource().getServer()).get(p.getUUID());
		p.sendSystemMessage(Component.literal("Balance: $" + Ui.money(money) + " (" + Ui.exact(money) + ")").withStyle(ChatFormatting.GOLD));
		return 1;
	}

	private static int change(CommandContext<CommandSourceStack> c, boolean give) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(c, "player");
		long amount = LongArgumentType.getLong(c, "amount");
		MoneyData data = MoneyData.get(c.getSource().getServer());
		if (give) {
			data.add(target.getUUID(), amount);
		} else if (!data.take(target.getUUID(), amount)) {
			c.getSource().sendFailure(Component.literal("That player does not have enough money"));
			return 0;
		}
		long now = data.get(target.getUUID());
		c.getSource().sendSuccess(() -> Component.literal(
				target.getName().getString() + " now has $" + Ui.money(now) + " (" + Ui.exact(now) + ")"), true);
		return 1;
	}

	private static int showPrice(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		ItemStack stack = p.getMainHandItem();
		if (stack.isEmpty()) {
			p.sendSystemMessage(Component.literal("Hold an item in your hand first").withStyle(ChatFormatting.RED));
			return 0;
		}
		long unit = Prices.unitSell(stack);
		if (unit <= 0) {
			p.sendSystemMessage(Component.literal("This item cannot be bought or sold").withStyle(ChatFormatting.RED));
			return 0;
		}
		Prices.Tier t = Prices.tierOf(stack.getItem());
		p.sendSystemMessage(stack.getHoverName().copy().withStyle(t.color)
				.append(Component.literal("  [" + t.label + "]  Sell $" + Ui.money(unit)
						+ " | Buy $" + Ui.money(Prices.buyPrice(stack.getItem()))).withStyle(ChatFormatting.YELLOW)));
		return 1;
	}
}
