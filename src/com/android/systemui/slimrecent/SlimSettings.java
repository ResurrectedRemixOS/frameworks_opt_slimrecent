/*
 * Copyright (C) 2026 The Resurrection Remix Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.slimrecent;

/** Settings.System names the slim recents panel reads. Launcher3's Recents screen writes them. */
public final class SlimSettings {
    public static final String RECENT_PANEL_GRAVITY = "recent_panel_gravity";
    public static final String RECENT_PANEL_SCALE_FACTOR = "recent_panel_scale_factor";
    public static final String RECENT_PANEL_EXPANDED_MODE = "recent_panel_expanded_mode";
    public static final String RECENT_PANEL_BG_COLOR = "recent_panel_bg_color";
    public static final String RECENT_PANEL_FAVORITES = "recent_panel_favorites";
    public static final String RECENT_CARD_BG_COLOR = "recent_card_bg_color";
    public static final String RECENTS_MAX_APPS = "recents_max_apps";
    public static final String SLIM_RECENTS_MEM_DISPLAY = "slim_recents_mem_display";
    public static final String SLIM_RECENTS_MEM_DISPLAY_LONG_CLICK_CLEAR =
            "slim_recents_mem_display_long_click_clear";
    public static final String SLIM_MEM_BAR_COLOR = "slim_mem_bar_color";
    public static final String SLIM_MEM_TEXT_COLOR = "slim_mem_text_color";
    public static final String SLIM_RECENTS_ICON_PACK = "slim_recents_icon_pack";
    public static final String SLIM_RECENTS_CORNER_RADIUS = "slim_recents_corner_radius";
    public static final String SLIM_RECENTS_BLACKLIST_VALUES = "slim_recents_blacklist_values";
    public static final String SLIM_RECENT_ENTER_EXIT_ANIMATION =
            "slim_recent_enter_exit_animation";
    public static final String SLIM_RECENT_RR_EMPTY_DRAWABLE = "slim_recent_rr_empty_drawable";

    private SlimSettings() {}
}
