package biz.donvi.jakesRTP;

import biz.donvi.jakesRTP.GeneralUtil.Pair;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.logging.Level;

import static biz.donvi.jakesRTP.PluginMain.*;

public class RandomTeleporter {

    final static String EXPLICIT_PERM_PREFIX = "jakesrtp.use.";

    // Dynamic settings
    public final  Map<String, DistributionSettings> distributionSettings;
    private final ArrayList<RtpSettings>            rtpSettings;

    // First join settings
    public final boolean     firstJoinRtp;
    public final RtpSettings firstJoinSettings;
    public final World       firstJoinWorld;
    // On death settings
    public final boolean     onDeathRtp;
    public final boolean     onDeathRespectBeds;
    public final boolean     onDeathRequirePermission;
    public final RtpSettings onDeathSettings;
    public final World       onDeathWorld;
    // Misc settings
    public final int         asyncWaitTimeout;

    // Logging settings
    public final boolean
        logRtpOnPlayerJoin,
        logRtpOnRespawn,
        logRtpOnCommand,
        logRtpOnForceCommand,
        logRtpForQueue;

    /**
     * Creating an instance of the RandomTeleporter object is required to be able to use the command.
     * On creation, all relevant parts of the config are loaded into memory.
     *
     * @throws Exception A generic exception for any issue had when creating the object.
     *                   I have NOT made my own exceptions, but instead have written different messages.
     */
    public RandomTeleporter(
        ConfigurationSection globalConfig,
        List<Pair<String, FileConfiguration>> rtpSections,
        List<Pair<String, FileConfiguration>> distributions
    ) throws Exception {
        // Distributions:
        this.distributionSettings = new HashMap<>();
        for (Pair<String, FileConfiguration> item : distributions) //todo replace this with a for each over files
            try {
                distributionSettings.put(item.key, new DistributionSettings(item.value));
            } catch (NullPointerException e) {
                log(Level.WARNING, "Could not load distribution settings " + item.key);
                e.printStackTrace();
            }
        // Modular settings:
        this.rtpSettings = new ArrayList<>();
        for (Pair<String, FileConfiguration> item : rtpSections) //todo replace this with a for each over files
            try {
                if (item.value.getBoolean("enabled"))
                    this.rtpSettings.add(new RtpSettings(item.value, item.key, distributionSettings));
                else infoLog("Not loading config " + item.key + " since it is marked disabled.");
            } catch (NullPointerException | JrtpBaseException e) {
                PluginMain.infoLog(
                    (e instanceof JrtpBaseException ? "Error: " + e.getMessage() + '\n' : "") +
                    "Whoops! Something in the config wasn't right, " +
                    this.rtpSettings.size() + " configs have been loaded thus far.");
            }
        // Static settings:
        if (firstJoinRtp = globalConfig.getBoolean("rtp-on-first-join.enabled", false)) {
            firstJoinSettings = getRtpSettingsByName(globalConfig.getString("rtp-on-first-join.settings"));
            World world = PluginMain.plugin.getServer().getWorld(
                Objects.requireNonNull(globalConfig.getString("rtp-on-first-join.world")));
            if (firstJoinSettings.getConfigWorlds().contains(world))
                firstJoinWorld = world;
            else throw new Exception("The RTP first join world is not an enabled world in the config's settings!");
        } else {
            firstJoinSettings = null;
            firstJoinWorld = null;
        }
        if (onDeathRtp = globalConfig.getBoolean("rtp-on-death.enabled", false)) {
            onDeathRespectBeds = globalConfig.getBoolean("rtp-on-death.respect-beds", true);
            onDeathSettings = getRtpSettingsByName(globalConfig.getString("rtp-on-death.settings"));
            onDeathRequirePermission = globalConfig.getBoolean("rtp-on-death.require-permission", true);
            World world = PluginMain.plugin.getServer().getWorld(
                Objects.requireNonNull(globalConfig.getString("rtp-on-death.world")));
            if (onDeathSettings.getConfigWorlds().contains(world))
                onDeathWorld = world;
            else throw new Exception("The RTP first join world is not an enabled world in the config's settings!");
        } else {
            onDeathRespectBeds = false;
            onDeathRequirePermission = false;
            onDeathSettings = null;
            onDeathWorld = null;
        }
        if (globalConfig.getBoolean("location-cache-filler.enabled", true))
            asyncWaitTimeout = globalConfig.getInt("location-cache-filler.async-wait-timeout", 5);
        else
            asyncWaitTimeout = 1; //Yes a hard coded default. If set to 0 and accidentally used, there would be issues.
        //So much logging...
        logRtpOnPlayerJoin = globalConfig.getBoolean("logging.rtp-on-player-join", true);
        logRtpOnRespawn = globalConfig.getBoolean("logging.rtp-on-respawn", true);
        logRtpOnCommand = globalConfig.getBoolean("logging.rtp-on-command", true);
        logRtpOnForceCommand = globalConfig.getBoolean("logging.rtp-on-force-command", true);
        logRtpForQueue = globalConfig.getBoolean("logging.rtp-for-queue", false);
    }

    /* ================================================== *\
                    RtpSettings ← Getters
    \* ================================================== */

    /**
     * Getter for the ArrayList of RtpSettings. This contains all settings that are done per config sections.
     *
     * @return The ArrayList of RtpSettings.
     */
    public ArrayList<RtpSettings> getRtpSettings() { return rtpSettings; }

    /**
     * Gets the list of RtpSettings names.
     *
     * @return A list of RtpSettings names.
     */
    public ArrayList<String> getRtpSettingsNames() {
        ArrayList<String> rtpSettings = new ArrayList<>();
        for (RtpSettings rtpSetting : this.rtpSettings)
            rtpSettings.add(rtpSetting.name);
        return rtpSettings;
    }

    /**
     * Gets the names of all {@code rtpSettings} usable by the given player.
     *
     * @param player The player to check for settings with.
     * @return All the names of rtpSettings that the given player can use.
     */
    public ArrayList<String> getRtpSettingsNamesForPlayer(Player player) {
        ArrayList<String> rtpSettings = new ArrayList<>();
        for (RtpSettings rtpSetting : this.rtpSettings)
            if (rtpSetting.commandEnabled && (
                !rtpSetting.requireExplicitPermission ||
                player.hasPermission(EXPLICIT_PERM_PREFIX + rtpSetting.name))
            ) rtpSettings.add(rtpSetting.name);
        return rtpSettings;
    }

    /**
     * Gets the {@code RtpSettings} that are being used by the given world. If more then one {@code RtpSettings}
     * objects are valid, the one with the highest priority will be returned.
     *
     * @param world World to get RTP settings for
     * @return The RtpSettings of that world
     * @throws NotPermittedException If the world does not exist.
     */
    public RtpSettings getRtpSettingsByWorld(World world) throws NotPermittedException {
        RtpSettings finSettings = null;
        for (RtpSettings settings : rtpSettings)
            for (World settingWorld : settings.getConfigWorlds())
                if (world.equals(settingWorld) && (
                    finSettings == null
                    || finSettings.priority < settings.priority)
                ) {
                    finSettings = settings;
                    break;
                }
        if (finSettings != null) return finSettings;
        else throw new NotPermittedException(Messages.NP_R_NOT_ENABLED.format("~ECW"));
    }


    /**
     * Gets the RtpSettings object that has the given name (as defined in the config).
     *
     * @param name The name of the settings
     * @return The Rtp settings object with the given name
     * @throws JrtpBaseException If no settings have the given name
     */
    public RtpSettings getRtpSettingsByName(String name) throws JrtpBaseException {
        for (RtpSettings settings : rtpSettings)
            if (settings.name.equals(name))
                return settings;
        throw new JrtpBaseException(Messages.NP_R_NO_RTPSETTINGS_NAME.format(name));
    }

    /**
     * Gets the RtpSettings that a specific player in a specific world should be using. This is intended to be
     * used for players running the rtp command, as it follows all rules that players are held to when rtp-ing.
     *
     * @param player The player whose information will be used to determine the relevant rtp settings
     * @return The RtpSettings for the player to use, normally for when they run the {@code /rtp} command.
     * @throws NotPermittedException If no settings can be used.
     */
    public RtpSettings getRtpSettingsByWorldForPlayer(Player player) throws NotPermittedException {
        RtpSettings finSettings = null;
        World playerWorld = player.getWorld();
        for (RtpSettings settings : rtpSettings)
            for (World settingWorld : settings.getConfigWorlds())
                //First, the world must be in the settings to become a candidate
                if (playerWorld.equals(settingWorld) &&
                    //Then we check if the settings are usable from the command
                    settings.commandEnabled &&
                    //Then we check the priority
                    (finSettings == null || finSettings.priority < settings.priority) &&
                    //Then we check if we require explicit perms
                    (!settings.requireExplicitPermission || player.hasPermission(EXPLICIT_PERM_PREFIX + settings.name))
                ) {
                    finSettings = settings;
                    break;
                }
        if (finSettings != null) return finSettings;
        else throw new NotPermittedException(Messages.NP_R_NOT_ENABLED.format("~ECP"));
    }

    /**
     * Gets the RtpSettings object that has the given name (as defined in the config) IF AND ONLY IF the settings with
     * the given name have property {@code commandEnabled} set to {@code true}, and the player either has the necessary
     * explicit permission for the settings, or the settings does not require one.
     *
     * @param player The player to find the settings for. Only settings this player can use will be returned.
     * @param name   The name of the settings to find.
     * @return The {@code rtpSettings} object with the matching name. If no valid {@code rtpSettings} is found, an
     * an exception will be thrown.
     * @throws NotPermittedException if no valid {@code rtpSettings} object is found.
     */
    public RtpSettings getRtpSettingsByNameForPlayer(Player player, String name) throws NotPermittedException {
        for (RtpSettings settings : rtpSettings)
            // First check if this settings can be called by a player command
            if (settings.commandEnabled &&
                // Then we need the names to match
                settings.name.equalsIgnoreCase(name) &&
                //Then we check if we require explicit perms
                (!settings.requireExplicitPermission || player.hasPermission(EXPLICIT_PERM_PREFIX + settings.name)))
                // Note: We never check priority because the name must be unique
                return settings;
        throw new NotPermittedException(Messages.NP_R_NO_RTPSETTINGS_NAME_FOR_PLAYER.format(name));
    }
    /* ================================================== *\
                    Rtp Locations ← Getters
    \* ================================================== */

//    /**
//     * This method acts as a bridge between this Minecraft specific class and my evenDistribution package
//     * by calling the appropriate method from the package, and forwarding the relevant configuration
//     * settings that have been saved in memory.
//     *
//     * @param rtpSettings The Rtp settings to use to get the random points
//     * @return A random X and Z coordinate pair.
//     * @throws Exception if a shape is not properly defined,
//     *                   though realistic error checking beforehand should prevent this issue
//     */
//    private int[] getRtpXZ(RtpSettings rtpSettings) throws Exception {
//        switch (rtpSettings.rtpRegionShape) {
//            case SQUARE:
//                if (rtpSettings.gaussianShrink == 0) return RandomCords.getRandXySquare(
//                    rtpSettings.maxRadius,
//                    rtpSettings.minRadius);
//                else return RandomCords.getRandXySquare(
//                    rtpSettings.maxRadius,
//                    rtpSettings.minRadius,
//                    rtpSettings.gaussianShrink,
//                    rtpSettings.gaussianCenter);
//            case CIRCLE:
//                return RandomCords.getRandXyCircle(
//                    rtpSettings.maxRadius,
//                    rtpSettings.minRadius,
//                    rtpSettings.gaussianShrink,
//                    rtpSettings.gaussianCenter);
//            case RECTANGLE:
//                //return getRtpXzRectangle(); //This will get un-commented once I write a method for rectangles
//            default:
//                throw new Exception("RTP Region shape not properly defined.");
//        }
//    }

    /**
     * Creates the potential RTP location. If this location happens to be safe, is will be the exact location that
     * the player gets teleported to (though that is unlikely as the {@code y} is {@code 255} by default). <p>
     * This method differs from {@code getRtpXZ()} because it includes the offset and returns a {@code Location}
     * whereas {@code getRtpZX()} only gets the initial {@code x} and {@code z}, and returns a coordinate pair.
     *
     * @param callFromLoc A location representing where the call originated from. This is used to get either the world
     *                    spawn, or player location for the position offset
     * @param rtpSettings The relevant settings for RTP
     * @return The first location to check the safety of, which may end up being the final teleport location
     * @throws Exception Unlikely, but still possible.
     */
    @SuppressWarnings("ConstantConditions")
    private Location getPotentialRtpLocation(Location callFromLoc, RtpSettings rtpSettings) throws Exception {
        int[] xz = rtpSettings.distribution.shape.getCords();
        int[] xzOffset;
        switch (rtpSettings.distribution.center) {
            case PLAYER_LOCATION:
                xzOffset = new int[]{
                    (int) callFromLoc.getX(),
                    (int) callFromLoc.getZ()};
                break;
            case WORLD_SPAWN:
                xzOffset = new int[]{
                    (int) callFromLoc.getWorld().getSpawnLocation().getX(),
                    (int) callFromLoc.getWorld().getSpawnLocation().getZ()};
                break;
            case PRESET_VALUE:
            default:
                xzOffset = new int[]{
                    rtpSettings.distribution.centerX,
                    rtpSettings.distribution.centerZ};
        }

        return new Location(
            callFromLoc.getWorld(),
            xz[0] + xzOffset[0],
            255,
            xz[1] + xzOffset[1]
        );
    }

    /**
     * Keeps getting potential teleport locations until one has been found.
     * A fail-safe is included to throw an exception if too many unsuccessful attempts have been made.
     * This method can bypass explicit permission checks.
     *
     * @param rtpSettings   The specific RtpSettings to get the location with.
     * @param callFromLoc   The location that the call originated from. Used to find the world spawn,
     *                      or player's current location.
     * @param takeFromQueue Should we attempt to take the location from the queue before finding one on the spot?
     *                      <p> - Set to {@code true} if all you care about is teleporting the player, as this method
     *                      will fall back to using a Location not from the queue if required.
     *                      <p> - Set to {@code false} if you are filling the queue, or it is known that the queue is
     *                      empty.
     * @return A random location that can be safely teleported to by a player.
     * @throws Exception Only two points of this code are expected to be able to throw an exception:
     *                   getWorldRtpSettings() will throw an exception if the world is not RTP enabled.
     *                   getRtpXZ() will throw an exception if the rtp shape is not defined.
     */
    public Location getRtpLocation(final RtpSettings rtpSettings, Location callFromLoc, final boolean takeFromQueue)
    throws Exception {
        //Part 1: Force destination world if not current world
        if (rtpSettings.forceDestinationWorld && callFromLoc.getWorld() != rtpSettings.destinationWorld)
            callFromLoc = rtpSettings.destinationWorld.getSpawnLocation();

        //Part 2: Quick error checking
        if (!rtpSettings.getConfigWorlds().contains(callFromLoc.getWorld()))
            throw new NotPermittedException(Messages.NP_R_NOT_ENABLED.format("~ECG"));

        //Part 3 option 1: The Queue Route.
        //If we want to take from the queue and the queue is enabled, go here.
        //TODO split this into two things:
        // First is if location caching is turned off
        // Second is if it can not be used because of a relative location. In this case we want to find a new pos async
        if (takeFromQueue && rtpSettings.useLocationQueue) {
            Location preselectedLocation = rtpSettings.getLocationQueue(callFromLoc.getWorld()).poll();
            if (preselectedLocation != null) {
                plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, ()
                    -> PluginMain.locFinderRunnable.syncNotify(), 100);
                return preselectedLocation;
            } else return getRtpLocation(rtpSettings, callFromLoc, false); /*Type: Recursive*/
        }

        //Part 3 option 2: The Normal Route.
        //If we need to find a NEW location (meaning we can't use the queue), go here.
        else {
            Location potentialRtpLocation;
            int randAttemptCount = 0;
            do {
                potentialRtpLocation = getPotentialRtpLocation(callFromLoc, rtpSettings);
                if (randAttemptCount++ > rtpSettings.maxAttempts)
                    throw new JrtpBaseException(Messages.NP_R_TOO_MANY_FAILED_ATTEMPTS.format());
            } while (
                Bukkit.isPrimaryThread() ?
                    !new SafeLocationFinderBukkitThread(
                        potentialRtpLocation,
                        rtpSettings.checkRadiusXZ,
                        rtpSettings.checkRadiusVert,
                        rtpSettings.lowBound,
                        rtpSettings.highBound
                    ).tryAndMakeSafe(rtpSettings.checkProfile) :
                    !new SafeLocationFinderOtherThread(
                        potentialRtpLocation,
                        rtpSettings.checkRadiusXZ,
                        rtpSettings.checkRadiusVert,
                        rtpSettings.lowBound,
                        rtpSettings.highBound,
                        asyncWaitTimeout
                    ).tryAndMakeSafe(rtpSettings.checkProfile));
            return potentialRtpLocation;
        }

    }

    /* ================================================== *\
                    Misc ← Workers
    \* ================================================== */

    /**
     * This will fill up the queue of safe teleport locations for the specified {@code RtpSettings} and {@code World}
     * combination, waiting (though only if we are not on the main thread) a predetermined amount of time between
     * finding each location.
     *
     * @param settings The rtpSettings to use for the world
     * @param world    The world to find the locations in. This MUST be an enabled world in the given settings.
     * @return The number of locations added to the queue. (The result can be ignored if deemed unnecessary)
     * @throws NotPermittedException Should not realistically get thrown, but may occur if the world is not
     *                               enabled in the settings.
     */
    public int fillQueue(RtpSettings settings, World world)
    throws JrtpBaseException, SafeLocationFinder.PluginDisabledException {
        try {
            int changesMade = 0;
            for (Queue<Location> locationQueue = settings.getLocationQueue(world);
                 locationQueue.size() < settings.cacheLocationCount;
                 changesMade++
            ) {
                PluginMain.locFinderRunnable.waitIfNonMainThread();

                long startTime = System.currentTimeMillis();

                Location rtpLocation = getRtpLocation(settings, world.getSpawnLocation(), false);
                locationQueue.add(rtpLocation);

                long endTime = System.currentTimeMillis();
                if (logRtpForQueue) infoLog(
                    "Rtp-for-queue triggered. No player will be teleported." +
                    " Location: " + GeneralUtil.locationAsString(rtpLocation, 1, false) +
                    " Time: " + (endTime - startTime) + " ms.");
            }
            return changesMade;
        } catch (SafeLocationFinder.PluginDisabledException pluginDisabledException) {
            throw pluginDisabledException;
        } catch (Exception exception) {
            if (exception instanceof JrtpBaseException) throw (JrtpBaseException) exception;
            else exception.printStackTrace();
            return 0;
        }
    }

}