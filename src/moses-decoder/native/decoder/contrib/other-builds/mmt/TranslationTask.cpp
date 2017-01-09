#include "moses/TranslationTask.h"
#include "moses/StaticData.h"
#include "moses/Sentence.h"
#include "moses/IOWrapper.h"
#include "moses/TranslationAnalysis.h"
#include "moses/TypeDef.h"
#include "moses/Util.h"
#include "moses/Timer.h"
#include "moses/InputType.h"
#include "moses/OutputCollector.h"
#include "moses/Incremental.h"
#include "moses/mbr.h"

#include "moses/Syntax/F2S/RuleMatcherCallback.h"
#include "moses/Syntax/F2S/RuleMatcherHyperTree.h"
#include "moses/Syntax/S2T/Parsers/RecursiveCYKPlusParser/RecursiveCYKPlusParser.h"
#include "moses/Syntax/S2T/Parsers/Scope3Parser/Parser.h"
#include "moses/Syntax/T2S/RuleMatcherSCFG.h"

#include "util/exception.hh"

using namespace std;

namespace Moses
{

boost::shared_ptr<std::vector<std::string> >
TranslationTask::
GetContextWindow() const
{
  return m_context;
}

void
TranslationTask::
SetContextWindow(boost::shared_ptr<std::vector<std::string> > const& cw)
{
  m_context = cw;
}

boost::shared_ptr<TranslationTask>
TranslationTask
::create(boost::shared_ptr<InputType> const& source)
{
  boost::shared_ptr<IOWrapper> nix;
  boost::shared_ptr<TranslationTask> ret(new TranslationTask(source, nix));
  ret->m_self = ret;
  ret->m_scope.reset(new ContextScope);
  return ret;
}

boost::shared_ptr<TranslationTask>
TranslationTask
::create(boost::shared_ptr<InputType> const& source,
         boost::shared_ptr<IOWrapper> const& ioWrapper)
{
  boost::shared_ptr<TranslationTask> ret(new TranslationTask(source, ioWrapper));
  ret->m_self = ret;
  ret->m_scope.reset(new ContextScope);
  return ret;
}

boost::shared_ptr<TranslationTask>
TranslationTask
::create(boost::shared_ptr<InputType> const& source,
         boost::shared_ptr<IOWrapper> const& ioWrapper,
         boost::shared_ptr<ContextScope> const& scope)
{
  boost::shared_ptr<TranslationTask> ret(new TranslationTask(source, ioWrapper));
  ret->m_self  = ret;
  ret->m_scope = scope;
  return ret;
}

TranslationTask
::TranslationTask(boost::shared_ptr<InputType> const& source,
                  boost::shared_ptr<IOWrapper> const& ioWrapper)
  : m_source(source) , m_ioWrapper(ioWrapper)
{
  m_options = source->options();
}

TranslationTask::~TranslationTask()
{ }


boost::shared_ptr<BaseManager>
TranslationTask
::SetupManager(SearchAlgorithm algo)
{
  boost::shared_ptr<BaseManager> manager;
  // StaticData const& staticData = StaticData::Instance();
  // if (algo == DefaultSearchAlgorithm) algo = staticData.options().search.algo;

  if (!is_syntax(algo)) {
    manager.reset(new Manager(this->self())); // phrase-based
    return manager;
  }

#define WITHOUT_SYNTAX

#ifndef WITHOUT_SYNTAX
  if (algo == SyntaxF2S || algo == SyntaxT2S) {
    // STSG-based tree-to-string / forest-to-string decoding (ask Phil Williams)
    typedef Syntax::F2S::RuleMatcherCallback Callback;
    typedef Syntax::F2S::RuleMatcherHyperTree<Callback> RuleMatcher;
    manager.reset(new Syntax::F2S::Manager<RuleMatcher>(this->self()));
  }

  else if (algo == SyntaxS2T) {
    // new-style string-to-tree decoding (ask Phil Williams)
    S2TParsingAlgorithm algorithm = m_options->syntax.s2t_parsing_algo;
    if (algorithm == RecursiveCYKPlus) {
      typedef Syntax::S2T::EagerParserCallback Callback;
      typedef Syntax::S2T::RecursiveCYKPlusParser<Callback> Parser;
      manager.reset(new Syntax::S2T::Manager<Parser>(this->self()));
    } else if (algorithm == Scope3) {
      typedef Syntax::S2T::StandardParserCallback Callback;
      typedef Syntax::S2T::Scope3Parser<Callback> Parser;
      manager.reset(new Syntax::S2T::Manager<Parser>(this->self()));
    } else UTIL_THROW2("ERROR: unhandled S2T parsing algorithm");
  }

  else if (algo == SyntaxT2S_SCFG) {
    // SCFG-based tree-to-string decoding (ask Phil Williams)
    typedef Syntax::F2S::RuleMatcherCallback Callback;
    typedef Syntax::T2S::RuleMatcherSCFG<Callback> RuleMatcher;
    manager.reset(new Syntax::T2S::Manager<RuleMatcher>(this->self()));
  }

  else if (algo == ChartIncremental) // Ken's incremental decoding
    manager.reset(new Incremental::Manager(this->self()));

  else // original SCFG manager
    manager.reset(new ChartManager(this->self()));
#else
  UTIL_THROW2("ERROR: requested syntax search algorithm, but compiled with WITHOUT_SYNTAX.");
#endif

  return manager;
}

AllOptions::ptr const&
TranslationTask::
options() const
{
  return m_options;
}

// TranslationTask const*
// TranslationTask::
// current() {
// #ifdef WITH_THREADS
//   return s_current.get();
// #else
//   return NULL;
// #endif
// }

void TranslationTask::Run()
{
// #ifdef WITH_THREADS
//   s_current.reset(this);
// #endif
  UTIL_THROW_IF2(!m_source || !m_ioWrapper,
                 "Base Instances of TranslationTask must be initialized with"
                 << " input and iowrapper.");

  const size_t translationId = m_source->GetTranslationId();

  // report wall time spent on translation
  Timer translationTime;
  translationTime.start();

  // report thread number
#if defined(WITH_THREADS) && defined(BOOST_HAS_PTHREADS)
  VERBOSE(2, "Translating line " << translationId << "  in thread id "
          << pthread_self() << endl);
#endif


  // execute the translation
  // note: this executes the search, resulting in a search graph
  //       we still need to apply the decision rule (MAP, MBR, ...)
  Timer initTime;
  initTime.start();

  boost::shared_ptr<BaseManager> manager = SetupManager(m_options->search.algo);

  VERBOSE(1, "Line " << translationId << ": Initialize search took "
          << initTime << " seconds total" << endl);

  manager->Decode();

  // new: stop here if m_ioWrapper is NULL. This means that the
  // owner of the TranslationTask will take care of the output
  // oh, and by the way, all the output should be handled by the
  // output wrapper along the lines of *m_iwWrapper << *manager;
  // Just sayin' ...
  if (m_ioWrapper == NULL) return;

  // we are done with search, let's look what we got
  OutputCollector* ocoll;
  Timer additionalReportingTime;
  additionalReportingTime.start();
  boost::shared_ptr<IOWrapper> const& io = m_ioWrapper;

  manager->OutputBest(io->GetSingleBestOutputCollector());

  // output word graph
  manager->OutputWordGraph(io->GetWordGraphCollector());

  // output search graph
  manager->OutputSearchGraph(io->GetSearchGraphOutputCollector());

  // ???
  manager->OutputSearchGraphSLF();

  // Output search graph in hypergraph format for Kenneth Heafield's
  // lazy hypergraph decoder; writes to stderr
  if (m_options->output.SearchGraphHG.size()) {
    size_t transId = manager->GetSource().GetTranslationId();
    string fname = io->GetHypergraphOutputFileName(transId);
    manager->OutputSearchGraphAsHypergraph(fname, PRECISION);
  }

  additionalReportingTime.stop();

  additionalReportingTime.start();

  // output n-best list
  manager->OutputNBest(io->GetNBestOutputCollector());

  //lattice samples
  manager->OutputLatticeSamples(io->GetLatticeSamplesCollector());

  // detailed translation reporting
  ocoll = io->GetDetailedTranslationCollector();
  manager->OutputDetailedTranslationReport(ocoll);

  ocoll = io->GetDetailTreeFragmentsOutputCollector();
  manager->OutputDetailedTreeFragmentsTranslationReport(ocoll);

  //list of unknown words
  manager->OutputUnknowns(io->GetUnknownsCollector());

  manager->OutputAlignment(io->GetAlignmentInfoCollector());

  // report additional statistics
  manager->CalcDecoderStatistics();
  VERBOSE(1, "Line " << translationId << ": Additional reporting took "
          << additionalReportingTime << " seconds total" << endl);
  VERBOSE(1, "Line " << translationId << ": Translation took "
          << translationTime << " seconds total" << endl);
  IFVERBOSE(2) {
    PrintUserTime("Sentence Decoding Time:");
  }

// #ifdef WITH_THREADS
//   s_current.release();
// #endif
}

}