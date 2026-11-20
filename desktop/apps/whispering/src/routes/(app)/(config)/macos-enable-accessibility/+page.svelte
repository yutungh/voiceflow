<script lang="ts">
	import { Button } from '@epicenter/ui/button';
	import { Badge } from '@epicenter/ui/badge';
	import * as Card from '@epicenter/ui/card';
	import SettingsIcon from '@lucide/svelte/icons/settings';
	import CheckIcon from '@lucide/svelte/icons/check';
	import ArrowLeft from '@lucide/svelte/icons/arrow-left';
	import { desktopServices } from '$lib/services';
	import { toast } from 'svelte-sonner';
	import { asShellCommand } from '$lib/services/desktop/command';
	import type { PageData } from './$types';
	import { goto } from '$app/navigation';

	let { data } = $props();
	const isAccessibilityGranted = $derived(data.isAccessibilityGranted);

	async function requestPermissionOrShowGuidance() {
		const { error } = await desktopServices.permissions.accessibility.request();

		if (error) {
			toast.error('Failed to open accessibility settings', {
				description:
					'Please enable Accessibility in System Settings > Privacy & Security > Accessibility manually',
				action: {
					label: 'Open Accessibility Settings',
					onClick: () => openSystemSettings(),
				},
			});
		}
	}

	async function openSystemSettings() {
		// Try opening System Settings directly (works on macOS 13+)
		const { error: commandError } = await desktopServices.command.execute(
			asShellCommand(
				'open x-apple.systemsettings:com.apple.SystemSettings.extension',
			),
		);

		if (commandError) {
			console.error('Failed to open System Settings:', commandError);

			// Fallback: Show detailed instructions
			toast.info('Open System Settings Manually', {
				description:
					'Click Apple menu → System Settings → Privacy & Security → Accessibility',
				duration: 10000,
			});
			return;
		}

		// Show helpful toast since we can't open directly to accessibility
		toast.info('System Settings Opened', {
			description:
				'Navigate to Privacy & Security > Accessibility to grant permissions.',
			duration: 8000,
		});
	}
</script>

<svelte:head>
	<title>MacOS Accessibility</title>
</svelte:head>

<main class="flex flex-1 items-center justify-center">
	<Card.Root class="w-full max-w-2xl">
		<Card.Header>
			<Card.Title class="text-xl">MacOS Accessibility</Card.Title>
			<Card.Description class="leading-7">
				Follow the steps below to re-enable VoiceFlow in your macOS
				Accessibility settings. This often is needed after installing new
				versions of VoiceFlow due to a macOS bug.
			</Card.Description>
		</Card.Header>
		<Card.Content>
			<div class="flex flex-col items-center gap-2">
				<ol
					class="text-muted-foreground list-inside list-decimal space-y-1 text-sm leading-7"
				>
					<li>
						Go to <span class="text-primary font-semibold tracking-tight">
							System Settings > Privacy & Security > Accessibility
						</span> or click the button below.
					</li>

					<li>
						Click on <span class="text-primary font-semibold tracking-tight"
							>VoiceFlow</span
						> and remove it using the minus icon (-).
					</li>
					<li>
						Re-add VoiceFlow by pressing the plus icon (+) and selecting <span
							class="text-primary font-semibold tracking-tight"
							>VoiceFlow.app</span
						>
					</li>
				</ol>
			</div>
		</Card.Content>
		<Card.Footer>
			{#if !isAccessibilityGranted}
				<div class="flex gap-3 w-full">
					<Button
						variant="outline"
						onclick={() => goto('/')}
						class="flex-1 text-sm"
					>
						<ArrowLeft class="size-4" />
						Back to Home
					</Button>
					<Button
						onclick={() => requestPermissionOrShowGuidance()}
						class="flex-1 text-sm"
					>
						<SettingsIcon class="size-4" />
						Request Permission
					</Button>
				</div>
			{:else}
				<div class="flex flex-col gap-3 w-full">
					<Badge variant="success">
						<CheckIcon class="size-4" />
						Accessibility permissions granted
					</Badge>
					<div class="flex gap-3">
						<Button
							variant="outline"
							onclick={() => goto('/')}
							class="flex-1 text-sm"
						>
							<ArrowLeft class="size-4" />
							Back to Home
						</Button>
						<Button
							onclick={() => openSystemSettings()}
							variant="outline"
							class="flex-1 text-sm"
						>
							<SettingsIcon class="size-4" />
							Open Settings
						</Button>
					</div>
				</div>
			{/if}
		</Card.Footer>
	</Card.Root>
</main>
