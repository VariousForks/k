package org.kframework.main;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.kframework.backend.Backend;
import org.kframework.backend.doc.DocumentationBackend;
import org.kframework.backend.html.HtmlBackend;
import org.kframework.backend.kil.KExpBackend;
import org.kframework.backend.latex.LatexBackend;
import org.kframework.backend.latex.PdfBackend;
import org.kframework.backend.maude.MaudeBackend;
import org.kframework.backend.symbolic.SymbolicBackend;
import org.kframework.backend.unparser.UnparserBackend;
import org.kframework.backend.xml.XmlBackend;
import org.kframework.compile.AddEval;
import org.kframework.compile.FlattenModules;
import org.kframework.compile.ResolveConfigurationAbstraction;
import org.kframework.compile.checks.CheckConfigurationCells;
import org.kframework.compile.checks.CheckRewrite;
import org.kframework.compile.checks.CheckVariables;
import org.kframework.compile.sharing.AutomaticModuleImportsTransformer;
import org.kframework.compile.sharing.DittoFilter;
import org.kframework.compile.tags.AddDefaultComputational;
import org.kframework.compile.tags.AddOptionalTags;
import org.kframework.compile.tags.AddStrictStar;
import org.kframework.compile.transformers.AddEmptyLists;
import org.kframework.compile.transformers.AddHeatingConditions;
import org.kframework.compile.transformers.AddK2SMTLib;
import org.kframework.compile.transformers.AddKCell;
import org.kframework.compile.transformers.AddKLabelConstant;
import org.kframework.compile.transformers.AddKLabelToString;
import org.kframework.compile.transformers.AddPredicates;
import org.kframework.compile.transformers.AddSemanticEquality;
import org.kframework.compile.transformers.AddSupercoolDefinition;
import org.kframework.compile.transformers.AddSuperheatRules;
import org.kframework.compile.transformers.AddSymbolicK;
import org.kframework.compile.transformers.AddTopCellConfig;
import org.kframework.compile.transformers.AddTopCellRules;
import org.kframework.compile.transformers.ContextsToHeating;
import org.kframework.compile.transformers.DesugarStreams;
import org.kframework.compile.transformers.FlattenSyntax;
import org.kframework.compile.transformers.FreezeUserFreezers;
import org.kframework.compile.transformers.RemoveBrackets;
import org.kframework.compile.transformers.ResolveAnonymousVariables;
import org.kframework.compile.transformers.ResolveBinder;
import org.kframework.compile.transformers.ResolveBlockingInput;
import org.kframework.compile.transformers.ResolveBuiltins;
import org.kframework.compile.transformers.ResolveFreshMOS;
import org.kframework.compile.transformers.ResolveFunctions;
import org.kframework.compile.transformers.ResolveHybrid;
import org.kframework.compile.transformers.ResolveListOfK;
import org.kframework.compile.transformers.ResolveOpenCells;
import org.kframework.compile.transformers.ResolveRewrite;
import org.kframework.compile.transformers.ResolveSupercool;
import org.kframework.compile.transformers.ResolveSyntaxPredicates;
import org.kframework.compile.transformers.StrictnessToContexts;
import org.kframework.compile.utils.CheckVisitorStep;
import org.kframework.compile.utils.CompilerStepDone;
import org.kframework.compile.utils.CompilerSteps;
import org.kframework.compile.utils.FunctionalAdaptor;
import org.kframework.compile.utils.MetaK;
import org.kframework.kil.Definition;
import org.kframework.kil.loader.DefinitionHelper;
import org.kframework.utils.Stopwatch;
import org.kframework.utils.errorsystem.KException;
import org.kframework.utils.errorsystem.KException.ExceptionType;
import org.kframework.utils.errorsystem.KException.KExceptionGroup;
import org.kframework.utils.file.FileUtil;
import org.kframework.utils.file.KPaths;
import org.kframework.utils.general.GlobalSettings;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.binary.BinaryStreamDriver;

public class KompileFrontEnd {

	public static String output;

	private static List<String> metadataParse(String tags) {
		String[] alltags = tags.split("\\s+");
		List<String> result = new ArrayList<String>();
		for (int i = 0; i < alltags.length; i++)
			result.add(alltags[i]);
		return result;
	}

	public static void kompile(String[] args) {
		KompileOptionsParser op = new KompileOptionsParser();

		CommandLine cmd = op.parse(args);

		// options: help
		if (cmd.hasOption("help"))
			org.kframework.utils.Error.helpExit(op.getHelp(), op.getOptions());

		if (cmd.hasOption("version")) {
			String msg = FileUtil.getFileContent(KPaths.getKBase(false) + "/bin/version.txt");
			System.out.println(msg);
			System.exit(0);
		}

        GlobalSettings.symbolicEquality = cmd.hasOption("symeq");
        GlobalSettings.SMT = cmd.hasOption("smt");
        GlobalSettings.matchingLogic = cmd.hasOption("ml");

		// set verbose
		if (cmd.hasOption("verbose"))
			GlobalSettings.verbose = true;

		if (cmd.hasOption("nofilename"))
			GlobalSettings.noFilename = true;

		if (cmd.hasOption("warnings"))
			GlobalSettings.hiddenWarnings = true;

		if (cmd.hasOption("transition"))
			GlobalSettings.transition = metadataParse(cmd.getOptionValue("transition"));
		if (cmd.hasOption("supercool"))
			GlobalSettings.supercool = metadataParse(cmd.getOptionValue("supercool"));
		if (cmd.hasOption("superheat"))
			GlobalSettings.superheat = metadataParse(cmd.getOptionValue("superheat"));

		if (cmd.hasOption("style")) {
			String style = cmd.getOptionValue("style");
			if (style.startsWith("+")) {
				GlobalSettings.style += style.replace("+", ",");
			} else {
				GlobalSettings.style = style;
			}
		}

		if (cmd.hasOption("addTopCell"))
			GlobalSettings.addTopCell = true;

		// set lib if any
		if (cmd.hasOption("lib")) {
			GlobalSettings.lib = cmd.getOptionValue("lib");
		}
		if (cmd.hasOption("syntax-module"))
			GlobalSettings.synModule = cmd.getOptionValue("syntax-module");

		String step = null;
		if (cmd.hasOption("step")) {
			step = cmd.getOptionValue("step");
		}

		if (cmd.hasOption("fromxml")) {
			// File xmlFile = new File(cmd.getOptionValue("fromxml"));
			// if (cmd.hasOption("lang"))
			// fromxml(xmlFile, cmd.getOptionValue("lang"), step);
			// else
			// fromxml(xmlFile, FileUtil.getMainModule(xmlFile.getName()), step);
			System.err.println("fromxml option not supported anymore");
			System.exit(0);
		}

		String def = null;
		if (cmd.hasOption("def"))
			def = cmd.getOptionValue("def");
		else {
			String[] restArgs = cmd.getArgs();
			if (restArgs.length < 1)
				GlobalSettings.kem.register(new KException(ExceptionType.ERROR, KExceptionGroup.CRITICAL, "You have to provide a file in order to compile!.", "command line", "System file."));
			else
				def = restArgs[0];
		}

		File mainFile = new File(def);
		GlobalSettings.mainFile = mainFile;
		GlobalSettings.mainFileWithNoExtension = mainFile.getAbsolutePath().replaceFirst("\\.k$", "").replaceFirst("\\.xml$", "");
		if (!mainFile.exists()) {
			File errorFile = mainFile;
			mainFile = new File(def + ".k");
			if (!mainFile.exists()) {
				String msg = "File: " + errorFile.getName() + "(.k) not found.";
				GlobalSettings.kem.register(new KException(ExceptionType.ERROR, KExceptionGroup.CRITICAL, msg, errorFile.getAbsolutePath(), "File system."));
			}
		}

		output = null;
		if (cmd.hasOption("output")) {
			output = cmd.getOptionValue("output");
		}

		String lang = null;
		if (cmd.hasOption("lang"))
			lang = cmd.getOptionValue("lang");
		else
			lang = FileUtil.getMainModule(mainFile.getName());

		// Matching Logic & Symbolic Calculus options
		GlobalSettings.symbolicEquality = cmd.hasOption("symeq");
		GlobalSettings.SMT = cmd.hasOption("smt");
		GlobalSettings.matchingLogic = cmd.hasOption("ml");
		
		if (DefinitionHelper.dotk == null) {
			try {
				DefinitionHelper.dotk = new File(mainFile.getCanonicalFile().getParent() + File.separator + ".k");
			} catch (IOException e) {
				GlobalSettings.kem.register(new KException(ExceptionType.ERROR, KExceptionGroup.CRITICAL, "Canonical file cannot be obtained for main file.", mainFile.getAbsolutePath(),
						"File system."));
			}
			DefinitionHelper.dotk.mkdirs();
		}

		
		Backend backend = null;
		if (cmd.hasOption("maudify")) {
			backend = new MaudeBackend(Stopwatch.sw);
		} else if (cmd.hasOption("latex")) {
			GlobalSettings.documentation = true;
			backend = new LatexBackend(Stopwatch.sw);
		} else if (cmd.hasOption("pdf")) {
			GlobalSettings.documentation = true;
			backend = new PdfBackend(Stopwatch.sw);
		} else if (cmd.hasOption("xml")) {
			GlobalSettings.xml = true;
			backend = new XmlBackend(Stopwatch.sw);
		} else if (cmd.hasOption("html")) {
			if (!cmd.hasOption("style")) {
				GlobalSettings.style = "k-definition.css";
			}
			GlobalSettings.documentation = true;
			backend = new HtmlBackend(Stopwatch.sw);
		} else if (cmd.hasOption("unparse")) {
			backend = new UnparserBackend(Stopwatch.sw);
		} else if (cmd.hasOption("kexp")) {
			backend = new KExpBackend(Stopwatch.sw);
		} else if (cmd.hasOption("doc")) {
			GlobalSettings.documentation = true;
			if (!cmd.hasOption("style")) {
				GlobalSettings.style = "k-documentation.css";
			}
			backend = new DocumentationBackend(Stopwatch.sw);
		} else if (cmd.hasOption("symbolic")) {
			backend = new SymbolicBackend(Stopwatch.sw);
			symbolicCompile(mainFile, lang, backend, step);
			verbose(cmd);
			System.exit(0);
		} else {
			if (output == null) {
				output = FileUtil.stripExtension(mainFile.getName()) + "-kompiled";
			}
			backend = new KompileBackend(Stopwatch.sw);
			DefinitionHelper.dotk = new File(output);
			DefinitionHelper.dotk.mkdirs();
		}

		if (backend != null) {
			genericCompile(mainFile, lang, backend, step);
		}


		verbose(cmd);
	}

	private static void verbose(CommandLine cmd) {
		if (GlobalSettings.verbose)
			Stopwatch.sw.printTotal("Total");
		GlobalSettings.kem.print();
		if (cmd.hasOption("loud"))
			System.out.println("Done.");
	}

	private static void symbolicCompile(File mainFile, String lang,
			Backend backend, String step) {
		org.kframework.kil.Definition javaDef;
		try {
			Stopwatch.sw.Start();
			javaDef = org.kframework.utils.DefinitionLoader.loadDefinition(mainFile, lang, backend.autoinclude());

			CompilerSteps<Definition> steps = new CompilerSteps<Definition>();
			if (GlobalSettings.verbose) {
				steps.setSw(Stopwatch.sw);
			}
			steps.add(new FirstStep(backend));
/*			steps.add(new CheckVisitorStep<Definition>(new CheckConfigurationCells()));
			steps.add(new RemoveBrackets());
			steps.add(new AddEmptyLists());
			steps.add(new CheckVisitorStep<Definition>(new CheckVariables()));
			steps.add(new CheckVisitorStep<Definition>(new CheckRewrite()));
			steps.add(new AutomaticModuleImportsTransformer());
			steps.add(new FunctionalAdaptor(new DittoFilter()));
			steps.add(new FlattenModules());
			steps.add(new StrictnessToContexts());
			steps.add(new FreezeUserFreezers());
			steps.add(new ContextsToHeating());
			steps.add(new AddSupercoolDefinition());
			steps.add(new AddHeatingConditions());
			steps.add(new AddSuperheatRules());
			steps.add(new DesugarStreams());
			steps.add(new ResolveFunctions());
			steps.add(new AddKCell());
			steps.add(new AddSymbolicK());
			if (GlobalSettings.symbolicEquality)
				steps.add(new AddSemanticEquality());
			// steps.add(new ResolveFresh());
			steps.add(new ResolveFreshMOS());
			steps.add(new AddTopCellConfig());
			if (GlobalSettings.addTopCell) {
				steps.add(new AddTopCellRules());
			}
			steps.add(new AddEval());
			steps.add(new ResolveBinder());
			steps.add(new ResolveAnonymousVariables());
			steps.add(new ResolveBlockingInput());
			steps.add(new AddK2SMTLib());
			steps.add(new AddPredicates());
			steps.add(new ResolveSyntaxPredicates());
			steps.add(new ResolveBuiltins());
			steps.add(new ResolveListOfK());
			steps.add(new FlattenSyntax());
			steps.add(new AddKLabelToString());
			steps.add(new AddKLabelConstant());
			steps.add(new ResolveHybrid());
			steps.add(new ResolveConfigurationAbstraction());
			steps.add(new ResolveOpenCells());
			steps.add(new ResolveRewrite());
			steps.add(new ResolveSupercool());
			steps.add(new AddStrictStar());
			steps.add(new AddDefaultComputational());
			steps.add(new AddOptionalTags());
*/			steps.add(new LastStep(backend));

			if (step == null) {
				step = backend.getDefaultStep();
			}
			try {
				javaDef = steps.compile(javaDef, step);
			} catch (CompilerStepDone e) {
				javaDef = (Definition) e.getResult();
			}

			backend.run(javaDef);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	private static void genericCompile(File mainFile, String lang, Backend backend, String step) {
		org.kframework.kil.Definition javaDef;
		try {
			Stopwatch.sw.Start();
			javaDef = org.kframework.utils.DefinitionLoader.loadDefinition(mainFile, lang, backend.autoinclude());

			CompilerSteps<Definition> steps = new CompilerSteps<Definition>();
			if (GlobalSettings.verbose) {
				steps.setSw(Stopwatch.sw);
			}
			steps.add(new FirstStep(backend));
			steps.add(new CheckVisitorStep<Definition>(new CheckConfigurationCells()));
			steps.add(new RemoveBrackets());
			steps.add(new AddEmptyLists());
			steps.add(new CheckVisitorStep<Definition>(new CheckVariables()));
			steps.add(new CheckVisitorStep<Definition>(new CheckRewrite()));
			steps.add(new AutomaticModuleImportsTransformer());
			steps.add(new FunctionalAdaptor(new DittoFilter()));
			steps.add(new FlattenModules());
			steps.add(new StrictnessToContexts());
			steps.add(new FreezeUserFreezers());
			steps.add(new ContextsToHeating());
			steps.add(new AddSupercoolDefinition());
			steps.add(new AddHeatingConditions());
			steps.add(new AddSuperheatRules());
			steps.add(new DesugarStreams());
			steps.add(new ResolveFunctions());
			steps.add(new AddKCell());
			steps.add(new AddSymbolicK());
			if (GlobalSettings.symbolicEquality)
				steps.add(new AddSemanticEquality());
			// steps.add(new ResolveFresh());
			steps.add(new ResolveFreshMOS());
			steps.add(new AddTopCellConfig());
			if (GlobalSettings.addTopCell) {
				steps.add(new AddTopCellRules());
			}
			steps.add(new AddEval());
			steps.add(new ResolveBinder());
			steps.add(new ResolveAnonymousVariables());
			steps.add(new ResolveBlockingInput());
			steps.add(new AddK2SMTLib());
			steps.add(new AddPredicates());
			steps.add(new ResolveSyntaxPredicates());
			steps.add(new ResolveBuiltins());
			steps.add(new ResolveListOfK());
			steps.add(new FlattenSyntax());
			steps.add(new AddKLabelToString());
			steps.add(new AddKLabelConstant());
			steps.add(new ResolveHybrid());
			steps.add(new ResolveConfigurationAbstraction());
			steps.add(new ResolveOpenCells());
			steps.add(new ResolveRewrite());
			steps.add(new ResolveSupercool());
			steps.add(new AddStrictStar());
			steps.add(new AddDefaultComputational());
			steps.add(new AddOptionalTags());
			steps.add(new LastStep(backend));

			if (step == null) {
				step = backend.getDefaultStep();
			}
			try {
				javaDef = steps.compile(javaDef, step);
			} catch (CompilerStepDone e) {
				javaDef = (Definition) e.getResult();
			}
			XStream xstream = new XStream(new BinaryStreamDriver());
			xstream.aliasPackage("k", "org.kframework.kil");

			xstream.toXML(MetaK.getConfiguration(javaDef), new FileOutputStream(DefinitionHelper.dotk.getAbsolutePath() + "/configuration.bin"));

			backend.run(javaDef);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// private static void lint(File mainFile, String mainModule) {
	// try {
	// File canonicalFile = mainFile.getCanonicalFile();
	// org.kframework.kil.Definition javaDef = org.kframework.utils.DefinitionLoader.parseDefinition(canonicalFile, mainModule, true);
	//
	// KlintRule lintRule = new UnusedName(javaDef);
	// lintRule.run();
	//
	// lintRule = new UnusedSyntax(javaDef);
	// lintRule.run();
	//
	// lintRule = new InfiniteRewrite(javaDef);
	// lintRule.run();
	// } catch (IOException e1) {
	// e1.printStackTrace();
	// } catch (Exception e1) {
	// e1.printStackTrace();
	// }
	// }

	// public static void pdfClean(String[] extensions) {
	// for (int i = 0; i < extensions.length; i++)
	// new File(GlobalSettings.mainFileWithNoExtension + extensions[i]).delete();
	// }

}
