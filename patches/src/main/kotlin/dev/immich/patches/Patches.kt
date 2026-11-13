package dev.immich.patches

import app.morphe.patcher.extensions.InstructionExtensions
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element
import java.lang.reflect.Modifier

private const val EXTENSION_PATH = "extensions/immich-media.mpe"

/** Temporary: lets adb query the injected provider directly while debugging. */
private const val DEBUG_EXPORT_PROVIDER = false
private const val HOOKS = "dev/immich/media/ImmichHooks"
private const val PROVIDER_CLASS = "dev.immich.media.ImmichMediaProvider"

private const val DISCORD_PACKAGE = "com.discord"
private const val DISCORD_VERSIONS = "351.11 - Stable"

private const val TUMBLR_PACKAGE = "com.tumblr"
private const val TUMBLR_VERSIONS = "47.0.0.108"

/**
 * Server configuration. Declared once so both the manifest patch and the bytecode patch
 * share the same option instances; the CLI sets the value on whichever patch it resolves.
 */
private val immichBaseUrl = stringOption(
    key = "immichBaseUrl",
    title = "Immich server URL",
    description = "Base URL of the Immich server, for example https://immich.example.com",
    default = "",
    required = true,
)

private val immichApiKey = stringOption(
    key = "immichApiKey",
    title = "Immich API key",
    description = "API key used to browse and download assets from Immich",
    default = "",
    required = true,
)

// region Discord

private val discordImmichResources = resourcePatch(
    name = "Immich media provider",
    description = "Registers the Immich backed media provider inside Discord's manifest.",
) {
    compatibleWith(DISCORD_PACKAGE to setOf(DISCORD_VERSIONS))

    execute {
        installProvider(this, "$DISCORD_PACKAGE.immich.media")
    }
}

val discordImmichMediaPatch = bytecodePatch(
    name = "Immich photo picker",
    description = "Replaces Discord's in-app gallery with a browser for an Immich server.",
) {
    compatibleWith(DISCORD_PACKAGE to setOf(DISCORD_VERSIONS))
    dependsOn(discordImmichResources)
    immichBaseUrl()
    immichApiKey()
    extendWith(EXTENSION_PATH)

    execute {
        // Discord 351.11:
        //   ContentResolverMedia$Companion.queryMedia(Context, QueryType, int, Uri, Integer, List)
        // The URI is parameter 3; replacing it makes the picker read from Immich while the
        // app keeps appending its own ids through ContentUris.withAppendedId.
        val queryMedia = mutableClassDefBy("Lcom/discord/media/utils/ContentResolverMedia\$Companion;")
            .methods
            .first { it.name == "queryMedia" }

        val contextRegister = queryMedia.parameterRegister(0)
        val uriRegister = queryMedia.parameterRegister(3)
        val scratch = queryMedia.scratchRegister()

        queryMedia.addSmali(
            0,
            """
                move-object/from16 v$scratch, v$uriRegister
                invoke-static {v$contextRegister, v$scratch}, L$HOOKS;->thumbBase(Landroid/content/Context;Landroid/net/Uri;)Landroid/net/Uri;
                move-result-object v$scratch
                move-object/from16 v$uriRegister, v$scratch
            """.trimIndent(),
        )

        // MediaResolver.resolveToUri$media_release(Uri, int, boolean, boolean, boolean,
        //     EncodingConfig, boolean, Continuation) uploads whatever URI the picker handed
        //     out, so translate our preview URI back to the original asset.
        val resolveToUri = mutableClassDefBy("Lcom/discord/media/utils/MediaResolver;")
            .methods
            .first { it.name == "resolveToUri\$media_release" }

        val resolveUriRegister = resolveToUri.parameterRegister(0)

        resolveToUri.addSmali(
            0,
            """
                invoke-static {v$resolveUriRegister}, L$HOOKS;->toOriginal(Landroid/net/Uri;)Landroid/net/Uri;
                move-result-object v$resolveUriRegister
            """.trimIndent(),
        )
    }
}

// endregion

// region Tumblr

private val tumblrImmichResources = resourcePatch(
    name = "Immich media provider",
    description = "Registers the Immich backed media provider inside Tumblr's manifest.",
) {
    compatibleWith(TUMBLR_PACKAGE to setOf(TUMBLR_VERSIONS))

    execute {
        installProvider(this, "$TUMBLR_PACKAGE.immich.media")
    }
}

val tumblrImmichMediaPatch = bytecodePatch(
    name = "Immich photo picker",
    description = "Replaces Tumblr's in-app gallery with a browser for an Immich server.",
) {
    compatibleWith(TUMBLR_PACKAGE to setOf(TUMBLR_VERSIONS))
    dependsOn(tumblrImmichResources)
    immichBaseUrl()
    immichApiKey()
    extendWith(EXTENSION_PATH)

    execute {
        // PostGalleryFragment.A5() returns the collection the gallery cursor is built on,
        // normally MediaStore.Files.getContentUri("external").
        val galleryBase = mutableClassDefBy("Lcom/tumblr/ui/fragment/PostGalleryFragment;")
            .methods
            .first { it.name == "A5" && it.parameterTypes.isEmpty() && it.returnType == "Landroid/net/Uri;" }

        galleryBase.replaceBody(
            """
                invoke-static {}, L$HOOKS;->galleryBase()Landroid/net/Uri;
                move-result-object v0
                return-object v0
            """.trimIndent(),
        )

        // plc.j(int mediaType, long id, Context) and the plc$a.g it delegates to resolved grid
        // thumbnails from MediaStore.Thumbnails. Hand back our preview for Immich ids and let
        // the original code run for everything else, so unrelated media keeps its thumbnails.
        val thumbnailEntryPoints = listOf(
            mutableClassDefBy("Lplc;").methods
                .first { it.name == "j" && it.parameterTypes.size == 3 && it.returnType == "Landroid/net/Uri;" },
            mutableClassDefBy("Lplc\$a;").methods
                .first { it.name == "g" && it.parameterTypes.size == 3 && it.returnType == "Landroid/net/Uri;" },
        )

        thumbnailEntryPoints.forEach { method ->
            method.injectImmichShortcut(
                hook = "thumbUri",
                descriptor = "(IJLandroid/content/Context;)Landroid/net/Uri;",
                arguments = method.callRegisters(),
                prefix = null,
            )
        }

        // Rows are dropped wherever the app checks that the _data file exists. Our _data
        // values are content URIs, so those checks have to be redirected. The cursor to
        // GalleryMedia conversion lives in plc.o, not in the fragment.
        val checkedClasses = listOf(
            "Lcom/tumblr/ui/fragment/PostGalleryFragment;",
            "Lplc;",
        )
        var redirectedChecks = 0
        checkedClasses.forEach { className ->
            mutableClassDefBy(className).methods.forEach { method ->
                val implementation = method.implementation ?: return@forEach
                implementation.instructions.indices.forEach { index ->
                    val instruction = implementation.instructions[index]
                    val reference = (instruction as? ReferenceInstruction)?.reference
                    if (reference is MethodReference &&
                        reference.name == "exists" &&
                        reference.definingClass == "Ljava/io/File;"
                    ) {
                        val register = (instruction as BuilderInstruction35c).registerC
                        method.replaceSmali(
                            index,
                            "invoke-static {v$register}, L$HOOKS;->exists(Ljava/io/File;)Z",
                        )
                        redirectedChecks++
                    }
                }
            }
        }
        check(redirectedChecks > 0) { "No File.exists() checks found in the gallery classes" }

        // ImageData.d(long) is how every post attachment URI is built, by concatenating the
        // MediaStore image collection with the id. Ours must point at the provider instead.
        val imageData = mutableClassDefBy("Lcom/tumblr/creation/model/ImageData\$a;")
            .methods
            .first { it.name == "b" && it.parameterTypes.size == 1 && it.returnType == "Ljava/lang/String;" }

        imageData.injectImmichShortcut(
            hook = "imageUri",
            descriptor = "(J)Ljava/lang/String;",
            arguments = "p1, p2",
            prefix = null,
        )

        // GalleryMedia's constructor rewrites the _data string as "file://" + Uri.getPath(),
        // which turns our content URIs into bogus file paths. Keep ours intact.
        val galleryMediaConstructor = mutableClassDefBy("Lcom/tumblr/model/GalleryMedia;")
            .methods
            .first {
                it.parameterTypes.size == 10 &&
                    it.parameterTypes[4] == "Ljava/lang/String;" &&
                    it.parameterTypes[6] == "Landroid/net/Uri;"
            }

        galleryMediaConstructor.preserveContentUriField("e")

        // NOTE: do not redirect File.exists() in the media/upload packages. sld.g() relies on
        // _data not existing as a file so that it falls back to ContentResolver.openInputStream;
        // forcing exists() true there sends it down a FileInputStream path that cannot work.

        // The posting and API stacks swallow their failures, leaving WorkManager to report a
        // bare SUCCESS. Route their logger into the trace so the API error is visible.
        val postingClasses = ArrayList<String>()
        classDefForEach { classDef ->
            val type = classDef.type
            if (type.startsWith("Lcom/tumblr/posting/") || type.startsWith("Lcom/tumblr/rumblr/")) {
                postingClasses.add(type)
            }
        }
        postingClasses.forEach { type ->
            mutableClassDefBy(type).methods.forEach { it.redirectLogger() }
        }

        // Anything in the app that calls BitmapFactory.decodeFile with our _data values fails,
        // because they are content URIs. The hook delegates for real paths, so redirecting the
        // whole app package is behaviour preserving.
        val decoders = ArrayList<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lcom/tumblr/")) {
                decoders.add(classDef.type)
            }
        }
        var decodeRewrites = 0
        decoders.forEach { type ->
            mutableClassDefBy(type).methods.forEach { method ->
                val implementation = method.implementation ?: return@forEach
                implementation.instructions.indices.forEach { index ->
                    val instruction = implementation.instructions[index]
                    val reference = (instruction as? ReferenceInstruction)?.reference
                    if (reference !is MethodReference ||
                        reference.definingClass != "Landroid/graphics/BitmapFactory;" ||
                        reference.name != "decodeFile"
                    ) {
                        return@forEach
                    }
                    val registers = instruction as BuilderInstruction35c
                    val call = if (reference.parameterTypes.size == 1) {
                        "invoke-static {v${registers.registerC}}, L$HOOKS;->decodeFile(Ljava/lang/String;)" +
                            "Landroid/graphics/Bitmap;"
                    } else {
                        "invoke-static {v${registers.registerC}, v${registers.registerD}}," +
                            " L$HOOKS;->decodeFile(Ljava/lang/String;Landroid/graphics/BitmapFactory\$Options;)" +
                            "Landroid/graphics/Bitmap;"
                    }
                    method.replaceSmali(index, call)
                    decodeRewrites++
                }
            }
        }
        check(decodeRewrites > 0) { "No BitmapFactory.decodeFile calls found in the app package" }

        // Tumblr swallows gallery and posting failures into its own logger, which reaches
        // neither logcat (tiny buffers) nor us. Route those calls into the trace file.
        listOf(
            "Lcom/tumblr/ui/fragment/PostGalleryFragment;",
            "Lplc;",
            "Lplc\$a;",
            "Lcom/tumblr/posts/postform/CanvasActivity;",
            "Lxij;",
            "Lsld;",
            "Lanc\$f;",
            "Lanc\$e;",
            "Lbt8;",
        ).forEach { className ->
            mutableClassDefByOrNull(className)?.methods?.forEach { it.redirectLogger() }
        }

        // GalleryMedia.d() is the "original media" URI for an item, used by the composer.
        val mediaUri = mutableClassDefBy("Lcom/tumblr/model/GalleryMedia;")
            .methods
            .first { it.name == "d" && it.parameterTypes.isEmpty() && it.returnType == "Landroid/net/Uri;" }

        mediaUri.injectImmichShortcut(
            hook = "mediaUri",
            descriptor = "(IJ)Landroid/net/Uri;",
            arguments = null,
            prefix = """
                iget v0, p0, Lcom/tumblr/model/GalleryMedia;->b:I
                iget-wide v1, p0, Lcom/tumblr/model/GalleryMedia;->a:J
            """.trimIndent(),
        )
    }
}

// endregion

/** Descriptor index of the first local register, i.e. where parameters stop. */
private fun MutableMethod.parameterBase(): Int {
    val implementation = implementation ?: error("$definingClass->$name has no implementation")
    var incoming = if (Modifier.isStatic(accessFlags)) 0 else 1
    parameterTypes.forEach { descriptor ->
        incoming += if (descriptor == "J" || descriptor == "D") 2 else 1
    }
    return implementation.registerCount - incoming
}

/** Absolute register holding the given declared parameter (0 based, excluding `this`). */
private fun MutableMethod.parameterRegister(index: Int): Int {
    var register = parameterBase()
    if (!Modifier.isStatic(accessFlags)) {
        register += 1
    }
    for (position in 0 until index) {
        register += if (parameterTypes[position] == "J" || parameterTypes[position] == "D") 2 else 1
    }
    return register
}

/** A low register that is guaranteed not to hold a parameter. */
private fun MutableMethod.scratchRegister(): Int {
    require(parameterBase() >= 2) { "$definingClass->$name has no safe scratch register" }
    return 0
}

private fun MutableMethod.addSmali(index: Int, smali: String) {
    with(InstructionExtensions) { addInstructions(index, smali) }
}

private fun MutableMethod.replaceSmali(index: Int, smali: String) {
    with(InstructionExtensions) { replaceInstruction(index, smali) }
}


/** Replaces the whole body of a method that has no try blocks or external branch targets. */
private fun MutableMethod.replaceBody(smali: String) {
    val implementation = implementation ?: error("$definingClass->$name has no implementation")
    require(implementation.tryBlocks.isEmpty()) { "$definingClass->$name has try blocks" }
    val count = implementation.instructions.size
    with(InstructionExtensions) {
        if (count > 0) {
            // List based overload lives on the implementation, the smali one on the method.
            implementation.removeInstructions(0, count)
        }
        addInstructions(0, smali)
    }
}

/** Comma separated register list covering every declared parameter, wide values included. */
private fun MutableMethod.callRegisters(): String {
    var register = parameterBase()
    if (!Modifier.isStatic(accessFlags)) {
        // p0 holds `this`, the declared parameters start one register later.
        register += 1
    }
    val registers = ArrayList<String>()
    parameterTypes.forEach { descriptor ->
        val width = if (descriptor == "J" || descriptor == "D") 2 else 1
        repeat(width) {
            registers.add("v$register")
            register++
        }
    }
    require(registers.isNotEmpty()) { "$definingClass->$name takes no parameters" }
    require(register <= 16) { "$definingClass->$name needs a /range invoke" }
    return registers.joinToString(", ")
}

/**
 * Prepends a call to an Immich hook that returns null for anything we do not own, falling
 * through to the original implementation in that case.
 */
private fun MutableMethod.injectImmichShortcut(
    hook: String,
    descriptor: String,
    arguments: String?,
    prefix: String?,
) {
    val implementation = implementation ?: error("$definingClass->$name has no implementation")
    val original = implementation.instructions.firstOrNull() ?: error("$definingClass->$name is empty")
    val label = "immichFallback"
    val invoke = "invoke-static {${arguments ?: "v0, v1, v2"}}, L$HOOKS;->$hook$descriptor"
    val body = buildString {
        if (prefix != null) {
            appendLine(prefix)
        }
        appendLine(invoke)
        appendLine("move-result-object v0")
        appendLine("if-eqz v0, :$label")
        appendLine("return-object v0")
    }.trimEnd()
    with(InstructionExtensions) {
        addInstructionsWithLabels(0, body, ExternalLabel(label, original))
    }
}

/**
 * Short-circuits the block that derives a GalleryMedia string field from its _data argument.
 *
 * The constructor computes {@code "file://" + Uri.parse(data).getPath()}, which destroys our
 * provider URIs. When the hook claims the value we store it verbatim and skip the block,
 * otherwise the original code runs untouched.
 *
 * Registers v1..v3 are safe to use here: every constructor parameter has already been written
 * to its field by the time this block starts.
 */
private fun MutableMethod.preserveContentUriField(fieldName: String) {
    val implementation = implementation ?: error("$definingClass->$name has no implementation")
    val instructions = implementation.instructions

    val blockStart = instructions.indexOfFirst { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference
        reference is MethodReference &&
            reference.definingClass == "Landroid/text/TextUtils;" &&
            reference.name == "isEmpty"
    }
    require(blockStart >= 0) { "$definingClass->$name: no TextUtils.isEmpty to anchor to" }

    val fieldWrites = instructions.indices.filter { index ->
        val reference = (implementation.instructions[index] as? ReferenceInstruction)?.reference
        reference is FieldReference &&
            reference.definingClass == definingClass &&
            reference.name == fieldName
    }
    require(fieldWrites.size >= 2) { "$definingClass->$name: expected two writes to $fieldName" }

    val blockEnd = fieldWrites.last() + 1
    require(blockEnd < instructions.size) { "$definingClass->$name: $fieldName write is last" }

    val dataRegister = parameterRegister(4)
    with(InstructionExtensions) {
        addInstructionsWithLabels(
            blockStart,
            """
                invoke-static {v$dataRegister}, L$HOOKS;->keepContentUri(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v1
                if-eqz v1, :immichStock
                iput-object v1, p0, $definingClass->$fieldName:Ljava/lang/String;
                goto :immichAfter
            """.trimIndent(),
            ExternalLabel("immichStock", instructions[blockStart]),
            ExternalLabel("immichAfter", instructions[blockEnd]),
        )
    }
}


/**
 * Replaces calls to Tumblr's obfuscated logger with our own hook of the same descriptor so
 * swallowed failures end up in the provider trace.
 */
private fun MutableMethod.redirectLogger(): Int {
    val implementation = implementation ?: return 0
    var redirected = 0
    implementation.instructions.indices.forEach { index ->
        val instruction = implementation.instructions[index]
        val reference = (instruction as? ReferenceInstruction)?.reference
        if (reference !is MethodReference || reference.definingClass != "Lg7c;") {
            return@forEach
        }
        val hook = when (reference.name) {
            "f" -> "logThrown(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V"
            "e" -> "logMessage(Ljava/lang/String;Ljava/lang/String;)V"
            else -> return@forEach
        }
        val registers = instruction as BuilderInstruction35c
        val arguments = ArrayList<String>(registers.registerCount)
        val encoded = intArrayOf(registers.registerC, registers.registerD, registers.registerE, registers.registerF)
        repeat(registers.registerCount) { arguments.add("v${encoded[it]}") }
        replaceSmali(index, "invoke-static {${arguments.joinToString(", ")}}, L$HOOKS;->$hook")
        redirected++
    }
    return redirected
}

/** Index of the first call to the given method. */
private fun MutableMethod.indexOfMethodCall(methodName: String, definingClass: String? = null): Int {
    implementation!!.instructions.forEachIndexed { index, instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference
        if (reference is MethodReference &&
            reference.name == methodName &&
            (definingClass == null || reference.definingClass == definingClass)
        ) {
            return index
        }
    }
    error("No call to $methodName in $definingClass->$name")
}

private fun installProvider(context: ResourcePatchContext, authority: String) {
    val baseUrl = immichBaseUrl.value ?: ""
    val apiKey = immichApiKey.value ?: ""

    context.document("AndroidManifest.xml").use { document ->
        val application = document.getElementsByTagName("application").item(0) as? Element
            ?: error("AndroidManifest.xml has no <application> element")

        removeElements(document, "provider", "android:authorities", authority)
        removeElements(document, "meta-data", "android:name", "immich.baseUrl")
        removeElements(document, "meta-data", "android:name", "immich.apiKey")

        val provider = document.createElement("provider")
        provider.setAttribute("android:name", PROVIDER_CLASS)
        provider.setAttribute("android:authorities", authority)
        provider.setAttribute("android:exported", if (DEBUG_EXPORT_PROVIDER) "true" else "false")
        provider.setAttribute("android:grantUriPermissions", "true")
        application.appendChild(provider)

        // The patched build is installed without the Play Store split set.
        val manifest = document.getDocumentElement()
        manifest.removeAttribute("android:requiredSplitTypes")
        manifest.removeAttribute("android:splitTypes")
        removeElements(document, "meta-data", "android:name", "com.android.vending.splits.required")
        removeElements(document, "meta-data", "android:name", "com.android.vending.splits")

        // Baked fallback (the v1 path): only present when values were provided.
        if (baseUrl.isNotEmpty()) {
            addMetaData(document, application, "immich.baseUrl", baseUrl)
        }
        if (apiKey.isNotEmpty()) {
            addMetaData(document, application, "immich.apiKey", apiKey)
        }

        ensureUsesPermission(document, "android.permission.INTERNET")

        // v2: configuration and media come from the Immich app's bridge provider.
        // The permission is signature level (only apps signed like the Immich build get
        // it) and <queries> is mandatory because Android 11+ hides other apps'
        // providers otherwise — the failure mode is an "Unknown authority" exception
        // that even shell testing cannot reproduce.
        ensureUsesPermission(document, "app.alextran.immich.permission.BRIDGE")
        ensureBridgeQuery(document)
    }
}

private fun ensureUsesPermission(document: org.w3c.dom.Document, permission: String) {
    val nodes = document.getElementsByTagName("uses-permission")
    val present = (0 until nodes.length).any { index ->
        (nodes.item(index) as? Element)?.getAttribute("android:name") == permission
    }
    if (!present) {
        val element = document.createElement("uses-permission")
        element.setAttribute("android:name", permission)
        document.getDocumentElement().insertBefore(
            element,
            document.getElementsByTagName("application").item(0),
        )
    }
}

private fun ensureBridgeQuery(document: org.w3c.dom.Document) {
    val queryNodes = document.getElementsByTagName("queries")
    for (index in 0 until queryNodes.length) {
        val queries = queryNodes.item(index) as? Element ?: continue
        val providers = queries.getElementsByTagName("provider")
        for (providerIndex in 0 until providers.length) {
            val authorities = (providers.item(providerIndex) as? Element)
                ?.getAttribute("android:authorities")
            if (authorities == "app.alextran.immich.bridge") {
                return
            }
        }
    }
    val queries = if (queryNodes.length > 0) {
        queryNodes.item(0) as Element
    } else {
        document.createElement("queries").also { document.getDocumentElement().appendChild(it) }
    }
    val provider = document.createElement("provider")
    provider.setAttribute("android:authorities", "app.alextran.immich.bridge")
    queries.appendChild(provider)
}

private fun addMetaData(
    context: org.w3c.dom.Document,
    application: Element,
    name: String,
    value: String,
) {
    val metaData = context.createElement("meta-data")
    metaData.setAttribute("android:name", name)
    metaData.setAttribute("android:value", value)
    application.appendChild(metaData)
}

private fun removeElements(
    context: org.w3c.dom.Document,
    tag: String,
    attribute: String,
    value: String,
) {
    val nodes = context.getElementsByTagName(tag)
    val matches = ArrayList<Element>()
    for (index in 0 until nodes.length) {
        val element = nodes.item(index) as? Element ?: continue
        if (element.getAttribute(attribute) == value) {
            matches.add(element)
        }
    }
    matches.forEach { it.parentNode?.removeChild(it) }
}
