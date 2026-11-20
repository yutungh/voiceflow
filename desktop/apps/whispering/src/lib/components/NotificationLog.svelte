<script module lang="ts">
	import { upsertLog, withoutLog, type LogEntry } from './notification-log';

	// VoiceFlow 0.9.12 (F1): warnings and errors only, one entry per item
	// (updated in place), at most 50; each can be removed, or all at once.
	const notificationLog = (() => {
		let isOpen = $state(false);
		let logs = $state<LogEntry[]>([]);
		return {
			get isOpen() {
				return isOpen;
			},
			set isOpen(value: boolean) {
				isOpen = value;
			},
			get logs() {
				return logs;
			},
			addLog: (log: UnifiedNotificationOptions) => {
				const next = upsertLog(logs, log);
				if (next !== logs) logs = next;
			},
			/** Removes the entry for notice `id` (a problem dismissed or fixed). */
			removeLog: (id: string) => {
				const next = withoutLog(logs, id);
				if (next.length !== logs.length) logs = next;
			},
			clearLogs: () => {
				logs = [];
			},
		};
	})();

	export { notificationLog };
</script>

<script lang="ts">
	import * as Alert from '@epicenter/ui/alert';
	import * as Dialog from '@epicenter/ui/dialog';
	import * as Empty from '@epicenter/ui/empty';
	import type { UnifiedNotificationOptions } from '$lib/services/isomorphic/notifications/types';
	import { ScrollArea } from '@epicenter/ui/scroll-area';
	import AlertCircle from '@lucide/svelte/icons/alert-circle';
	import AlertTriangle from '@lucide/svelte/icons/alert-triangle';
	import BellIcon from '@lucide/svelte/icons/bell';
	import XIcon from '@lucide/svelte/icons/x';
	import { Button } from '@epicenter/ui/button';
	import { goto } from '$app/navigation';
	import { mode } from 'mode-watcher';

	type LogAction =
		| UnifiedNotificationOptions['action']
		| UnifiedNotificationOptions['secondaryAction'];

	// VoiceFlow: a logged problem keeps its fix buttons (e.g. Retry tidy-up),
	// so it can still be acted on after its toast is gone.
	function runAction(action: LogAction) {
		if (!action) return;
		if (action.type === 'button') {
			notificationLog.isOpen = false;
			void action.onClick();
		} else if (action.type === 'link') {
			notificationLog.isOpen = false;
			void goto(action.href);
		}
	}
</script>

<Dialog.Root bind:open={notificationLog.isOpen}>
	<Dialog.Content class="max-w-md">
		<Dialog.Header>
			<Dialog.Title>Notifications</Dialog.Title>
			<Dialog.Description>Warnings and problems, newest first</Dialog.Description>
		</Dialog.Header>

		{#if notificationLog.logs.length > 0}
			<div class="flex justify-end">
				<Button size="sm" variant="ghost" onclick={() => notificationLog.clearLogs()}>
					Clear all
				</Button>
			</div>
		{/if}

		<ScrollArea
			class="h-[60vh] overflow-y-auto rounded-md border bg-background p-4"
			data-sonner-toaster
			data-theme={mode.current}
			data-rich-colors="true"
		>
			{#each [...notificationLog.logs].reverse() as log (log.key)}
				<Alert.Root
					class="mb-2 last:mb-0"
					data-sonner-toast
					data-type={log.variant}
					data-styled="true"
					data-mounted="true"
				>
					<div class="flex items-center gap-3">
						{#if log.variant === 'error'}
							<div data-icon class="text-destructive">
								<AlertCircle class="size-4" />
							</div>
						{:else}
							<div data-icon class="text-warning">
								<AlertTriangle class="size-4" />
							</div>
						{/if}
						<div class="min-w-0 flex-1">
							<Alert.Title
								class="text-sm font-medium leading-none tracking-tight"
							>
								{log.title}
							</Alert.Title>
							<Alert.Description class="mt-1 text-sm text-muted-foreground">
								{log.description}
							</Alert.Description>
							{#if log.action?.type === 'button' || log.action?.type === 'link' || log.secondaryAction}
								<div class="mt-2 flex flex-wrap gap-2">
									{#each [log.action, log.secondaryAction] as action}
										{#if action && action.type !== 'more-details'}
											<Button
												size="sm"
												variant={action === log.action ? 'default' : 'outline'}
												onclick={() => runAction(action)}
											>
												{action.label}
											</Button>
										{/if}
									{/each}
								</div>
							{/if}
						</div>
						<button
							type="button"
							class="text-muted-foreground hover:text-foreground flex-none self-start rounded p-1"
							aria-label="Remove this notification"
							title="Remove"
							onclick={() => notificationLog.removeLog(log.key)}
						>
							<XIcon class="size-4" />
						</button>
					</div>
				</Alert.Root>
			{/each}

			{#if notificationLog.logs.length === 0}
				<Empty.Root class="h-32">
					<Empty.Header>
						<Empty.Media variant="icon">
							<BellIcon />
						</Empty.Media>
						<Empty.Title>Nothing here</Empty.Title>
						<Empty.Description>
							Warnings and problems show here until they are fixed or cleared.
						</Empty.Description>
					</Empty.Header>
				</Empty.Root>
			{/if}
		</ScrollArea>
	</Dialog.Content>
</Dialog.Root>

<style>
	:global([data-slot='dialog-content'] [data-sonner-toast]) {
		position: relative;
	}
</style>
