<script lang="ts" module>
	import { cn, type WithElementRef } from '#/utils/utils.js';
	import type {
		HTMLAnchorAttributes,
		HTMLButtonAttributes,
	} from 'svelte/elements';
	import { type VariantProps, tv } from 'tailwind-variants';

	export const buttonVariants = tv({
		base: "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 aria-invalid:border-destructive inline-flex shrink-0 items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium outline-none transition-[background-color,box-shadow,color,transform] duration-150 ease-out active:scale-[0.96] disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 [&_svg:not([class*='size-'])]:size-4 [&_svg]:pointer-events-none [&_svg]:shrink-0",
		variants: {
			variant: {
				// B3.1: the primary is tonal (tinted fill, deep teal text, 1 px teal edge), one per view.
				default:
					'bg-primary-tint text-primary-ink font-semibold shadow-[inset_0_0_0_1px_var(--primary-tint-border)] hover:bg-primary-tint-hover',
				destructive:
					'bg-destructive text-destructive-foreground hover:bg-destructive/90 focus-visible:outline-destructive',
				// Secondary: surface fill with the card ring as a neutral edge.
				outline:
					'bg-card text-foreground shadow-card hover:bg-muted hover:text-foreground',
				secondary:
					'bg-secondary text-secondary-foreground shadow-card hover:bg-muted',
				// Tertiary: text only.
				ghost: 'text-muted-foreground hover:bg-muted hover:text-foreground',
			},
			size: {
				default: 'h-9 px-3.5 py-2 has-[>svg]:px-3',
				sm: 'h-8 gap-1.5 rounded-md px-3 has-[>svg]:px-2.5',
				lg: 'h-10 rounded-md px-6 has-[>svg]:px-4',
				icon: 'size-9',
				'icon-xs': "size-6 [&_svg:not([class*='size-'])]:size-3",
				'icon-sm': 'size-8',
				'icon-lg': 'size-10',
			},
		},
		defaultVariants: {
			variant: 'default',
			size: 'default',
		},
	});

	export type ButtonVariant = VariantProps<typeof buttonVariants>['variant'];
	export type ButtonSize = VariantProps<typeof buttonVariants>['size'];

	export type ButtonProps = WithElementRef<HTMLButtonAttributes> &
		WithElementRef<HTMLAnchorAttributes> & {
			variant?: ButtonVariant;
			size?: ButtonSize;
			/**
			 * Tooltip text to display on hover.
			 * Requires a parent `<Tooltip.Provider>` in the component tree.
			 * Wrap your app root with `<Tooltip.Provider>` to enable tooltip coordination.
			 */
			tooltip?: string;
		};
</script>

<script lang="ts">
	import * as Tooltip from '#/tooltip';

	let {
		class: className,
		variant = 'default',
		size = 'default',
		ref = $bindable(null),
		href = undefined,
		type = 'button',
		disabled,
		children,
		tooltip,
		...restProps
	}: ButtonProps = $props();
</script>

{#snippet buttonContent(tooltipProps?: Record)}
	{#if href}
		<a
			bind:this={ref}
			data-slot="button"
			class={cn(buttonVariants({ variant, size }), className)}
			href={disabled ? undefined : href}
			aria-disabled={disabled}
			role={disabled ? 'link' : undefined}
			tabindex={disabled ? -1 : undefined}
			{...tooltipProps}
			{...restProps}
		>
			{@render children?.()}
		</a>
	{:else}
		<button
			bind:this={ref}
			data-slot="button"
			class={cn(buttonVariants({ variant, size }), className)}
			{type}
			{disabled}
			{...tooltipProps}
			{...restProps}
		>
			{@render children?.()}
		</button>
	{/if}
{/snippet}

<!--
	When using the tooltip prop, this component requires a parent Tooltip.Provider.
	Wrap your app root with <Tooltip.Provider> to enable tooltip coordination.
-->
{#if tooltip}
	<Tooltip.Root>
		<Tooltip.Trigger>
			{#snippet child({ props })}
				{@render buttonContent(props)}
			{/snippet}
		</Tooltip.Trigger>
		<Tooltip.Content class="max-w-xs text-center">
			{tooltip}
		</Tooltip.Content>
	</Tooltip.Root>
{:else}
	{@render buttonContent()}
{/if}
